# simple_sound_player.py
import threading
import os
import subprocess
import asyncio
import logging
from functools import lru_cache
from typing import Optional
from concurrent.futures import ThreadPoolExecutor

logger = logging.getLogger(__name__)

# Try to import VLC player
try:
    from vlc_player import VLCPlayer
    VLC_AVAILABLE = True
except ImportError:
    VLC_AVAILABLE = False
    VLCPlayer = None

class SimpleSoundPlayer:
    def __init__(self, sound_config: dict, output_device_id: int = None):
        self.sound_paths = sound_config
        self.output_device_id = output_device_id
        self._lock = threading.Lock()
        # Dedicated executor for audio playback (1 thread is enough for sequential playback)
        self._audio_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="AudioPlayer")
        
        # Try to detect ALSA device name (similar to whisplay-ai-chatbot)
        self.alsa_device = None
        # Cache volume control name để tránh dò lại mỗi lần (chỉ dò một lần khi khởi tạo)
        self.volume_control_name = None
        self.volume_card_num = None
        try:
            # Try to find wm8960 device (Whisplay uses card 1 by default, but check all)
            result = subprocess.run(['aplay', '-l'], capture_output=True, text=True, timeout=2)
            if result.returncode == 0:
                # First, try to find wm8960 device
                for line in result.stdout.split('\n'):
                    if 'wm8960' in line.lower():
                        # Extract card number (e.g., "card 1: wm8960soundcard")
                        parts = line.split()
                        if 'card' in parts:
                            card_idx = parts.index('card')
                            if card_idx + 1 < len(parts):
                                card_num = parts[card_idx + 1].rstrip(':')
                                self.alsa_device = f"hw:{card_num},0"
                                logger.info(f"✅ SimpleSoundPlayer: Detected WM8960 ALSA device: {self.alsa_device}")
                                break
                
                # If not found, use card 1 (Whisplay default) or first available card
                if not self.alsa_device:
                    # Check if card 1 exists (Whisplay default)
                    if 'card 1:' in result.stdout:
                        self.alsa_device = "hw:1,0"
                        logger.info(f"✅ SimpleSoundPlayer: Using card 1 (Whisplay default): {self.alsa_device}")
                    else:
                        # Use first card found
                        for line in result.stdout.split('\n'):
                            if 'card' in line and ':' in line:
                                parts = line.split()
                                if 'card' in parts:
                                    card_idx = parts.index('card')
                                    if card_idx + 1 < len(parts):
                                        card_num = parts[card_idx + 1].rstrip(':')
                                        if card_num.isdigit():
                                            self.alsa_device = f"hw:{card_num},0"
                                            logger.info(f"✅ SimpleSoundPlayer: Using first available card: {self.alsa_device}")
                                            break
        except Exception as e:
            logger.warning(f"⚠️ Could not detect ALSA device: {e}")
        
        # Fallback to Whisplay default (card 1) or system default
        if not self.alsa_device:
            # Try card 1 first (Whisplay default), then system default
            try:
                result = subprocess.run(['aplay', '-l'], capture_output=True, text=True, timeout=1)
                if 'card 1:' in result.stdout:
                    self.alsa_device = "hw:1,0"
                    logger.info(f"✅ SimpleSoundPlayer: Using card 1 (Whisplay default fallback): {self.alsa_device}")
                else:
                    self.alsa_device = "default"
                    logger.info(f"✅ SimpleSoundPlayer: Using system default ALSA device")
            except:
                self.alsa_device = "default"
                logger.info(f"✅ SimpleSoundPlayer: Using system default ALSA device (fallback)")
        
        logger.info(f"✅ Simple Sound Player initialized (ALSA device: {self.alsa_device})")
        
        # Initialize VLC player if available
        self.vlc_player = None
        if VLC_AVAILABLE:
            try:
                self.vlc_player = VLCPlayer(alsa_device=self.alsa_device)
                if self.vlc_player.is_available():
                    logger.info("✅ VLC player available for audio playback")
                else:
                    logger.debug("⚠️ VLC not installed, will use mpg123/ffmpeg fallback")
                    self.vlc_player = None
            except Exception as e:
                logger.debug(f"⚠️ Could not initialize VLC player: {e}")
                self.vlc_player = None
        
        # Dò tìm volume control name ngay khi khởi tạo (chỉ một lần)
        self._detect_volume_control()

    @lru_cache(maxsize=16)
    def _get_audio_file_path(self, file_path: str):
        """Returns the file path if it exists"""
        if not os.path.exists(file_path):
            logger.error(f"❌ Sound file not found: {file_path}")
            return None
        return file_path
        
    def _blocking_play_task(self, file_path: str, temp_volume: int = None):
        """
        Blocking function to play audio using aplay/paplay, intended for an executor.

        Args:
            file_path: Path to audio file
            temp_volume: Temporary volume for this sound (None = use current volume)
        """
        file_path = self._get_audio_file_path(file_path)
        if file_path is None:
            return

        # Use a lock to prevent multiple sounds from playing over each other
        # Try to acquire lock non-blocking first (skip if another sound is playing)
        lock_acquired = self._lock.acquire(blocking=False)
        if not lock_acquired:
            logger.debug(f"⚠️ [Audio] Another sound is playing, skipping: {os.path.basename(file_path)}")
            return

        # Save and set temporary volume if specified
        original_volume = None
        if temp_volume is not None and self.volume_control_name and self.volume_card_num:
            try:
                # Get current volume
                result = subprocess.run(
                    ['amixer', '-c', self.volume_card_num, 'sget', self.volume_control_name],
                    capture_output=True, text=True, timeout=2
                )
                if result.returncode == 0:
                    # Parse volume from amixer output
                    import re
                    match = re.search(r'\[(\d+)%\]', result.stdout)
                    if match:
                        original_volume = int(match.group(1))
                        # Set temporary volume
                        subprocess.run(
                            ['amixer', '-c', self.volume_card_num, 'sset', self.volume_control_name, f'{temp_volume}%'],
                            capture_output=True, timeout=2
                        )
                        logger.debug(f"🔊 Temp volume: {original_volume}% -> {temp_volume}%")
            except Exception as e:
                logger.debug(f"⚠️ Could not set temp volume: {e}")
                original_volume = None

        try:
            logger.debug(f"🔊 [MP3] Starting playback: {os.path.basename(file_path)} (ALSA device: {self.alsa_device})")
            
            # Try VLC first (best compatibility and quality), then fallback to mpg123/ffmpeg
            if file_path.endswith('.mp3') or file_path.endswith('.wav') or file_path.endswith('.ogg') or file_path.endswith('.flac'):
                # Try VLC first if available
                if self.vlc_player and self.vlc_player.is_available():
                    try:
                        logger.debug(f"🎵 [VLC] Attempting to play with VLC: {os.path.basename(file_path)}")
                        if self.vlc_player.play_file(file_path, blocking=True):
                            logger.debug(f"✅ [VLC] Successfully played: {os.path.basename(file_path)}")
                            return  # Success, exit early
                        else:
                            logger.debug(f"⚠️ [VLC] Failed, trying mpg123/ffmpeg fallback")
                    except Exception as e:
                        logger.debug(f"⚠️ [VLC] Error: {e}, trying mpg123/ffmpeg fallback")
            
            # Try using mpg123 first (much faster than ffmpeg for MP3), then fallback to ffmpeg
            if file_path.endswith('.mp3'):
                # Try mpg123 first (fastest for MP3 playback)
                try:
                    # mpg123 can output directly to ALSA device, much faster than ffmpeg
                    # Sanitize file path to prevent command injection
                    safe_file_path = self._sanitize_file_path(file_path)
                    if safe_file_path is None:
                        logger.error(f"❌ [MP3] Invalid file path: {file_path}")
                        return

                    mpg123_cmd = ['mpg123', '-q', '-a', self.alsa_device, safe_file_path]
                    result = subprocess.run(mpg123_cmd, check=False, timeout=10,
                                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    if result.returncode == 0:
                        logger.debug(f"✅ [MP3] Successfully played with mpg123: {os.path.basename(file_path)}")
                        return  # Success, exit early
                    else:
                        logger.debug(f"⚠️ [MP3] mpg123 failed (code {result.returncode}), trying ffmpeg fallback")
                except FileNotFoundError:
                    logger.debug(f"⚠️ [MP3] mpg123 not found, using ffmpeg fallback")
                except Exception as e:
                    logger.debug(f"⚠️ [MP3] mpg123 error: {e}, trying ffmpeg fallback")
                
                # Fallback to ffmpeg if mpg123 not available or failed
                try:
                    # Sanitize file path
                    safe_file_path = self._sanitize_file_path(file_path)
                    if safe_file_path is None:
                        logger.error(f"❌ [MP3] Invalid file path: {file_path}")
                        return

                    # Use ffmpeg to convert and play MP3 (slower but more compatible)
                    # Try stereo first (device supports Front Left - Front Right)
                    ffmpeg_cmd = ['ffmpeg', '-i', safe_file_path, '-f', 's16le', '-ar', '16000', '-ac', '2', '-']
                    aplay_cmd = ['aplay', '-D', self.alsa_device, '-f', 'S16_LE', '-r', '16000', '-c', '2', '-']
                    
                    ffmpeg_proc = subprocess.Popen(ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                    aplay_proc = subprocess.Popen(aplay_cmd, stdin=ffmpeg_proc.stdout, stderr=subprocess.PIPE)
                    ffmpeg_proc.stdout.close()
                    
                    # Wait for processes
                    aplay_returncode = aplay_proc.wait()
                    ffmpeg_returncode = ffmpeg_proc.wait()
                    
                    if aplay_returncode == 0 and ffmpeg_returncode == 0:
                        logger.debug(f"✅ [MP3] Successfully played with ffmpeg (stereo): {os.path.basename(file_path)}")
                    else:
                        # Fallback to mono if stereo fails
                        logger.debug(f"⚠️ [MP3] Stereo failed, trying mono...")
                        ffmpeg_cmd = ['ffmpeg', '-i', safe_file_path, '-f', 's16le', '-ar', '16000', '-ac', '1', '-']
                        aplay_cmd = ['aplay', '-D', self.alsa_device, '-f', 'S16_LE', '-r', '16000', '-c', '1', '-']
                        
                        ffmpeg_proc = subprocess.Popen(ffmpeg_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                        aplay_proc = subprocess.Popen(aplay_cmd, stdin=ffmpeg_proc.stdout, stderr=subprocess.PIPE)
                        ffmpeg_proc.stdout.close()
                        
                        aplay_returncode = aplay_proc.wait()
                        ffmpeg_returncode = ffmpeg_proc.wait()
                        
                        if aplay_returncode == 0 and ffmpeg_returncode == 0:
                            logger.debug(f"✅ [MP3] Successfully played with ffmpeg (mono): {os.path.basename(file_path)}")
                        else:
                            logger.error(f"❌ [MP3] Playback failed for: {os.path.basename(file_path)}")
                except Exception as e:
                    logger.error(f"❌ [MP3] Exception during playback: {e}", exc_info=True)
            else:
                # For WAV files, use aplay directly
                logger.debug(f"🔊 [WAV] Playing with aplay: {file_path}")
                result = subprocess.run(['aplay', '-D', self.alsa_device, file_path], 
                                     check=False, timeout=30, 
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode == 0:
                    logger.debug(f"✅ [WAV] Successfully played: {os.path.basename(file_path)}")
                else:
                    logger.error(f"❌ [WAV] aplay failed with return code {result.returncode}: {result.stderr.decode('utf-8', errors='ignore')}")
        except subprocess.TimeoutExpired:
            logger.error(f"❌ [MP3/WAV] Playback timeout for {file_path}")
        except subprocess.CalledProcessError as e:
            logger.error(f"❌ [MP3/WAV] Playback error for {file_path}: {e}")
        except FileNotFoundError as e:
            # Fallback to paplay (PulseAudio) if aplay not available
            logger.warning(f"⚠️ [MP3/WAV] aplay not found ({e}), trying paplay")
            try:
                result = subprocess.run(['paplay', file_path], check=False, timeout=30,
                                     stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                if result.returncode == 0:
                    logger.debug(f"✅ [MP3/WAV] Successfully played with paplay: {os.path.basename(file_path)}")
                else:
                    logger.error(f"❌ [MP3/WAV] paplay failed: {result.stderr.decode('utf-8', errors='ignore')}")
            except Exception as e2:
                logger.error(f"❌ [MP3/WAV] paplay also failed: {e2}")
        finally:
            # Restore original volume if it was changed
            if original_volume is not None and self.volume_control_name and self.volume_card_num:
                try:
                    subprocess.run(
                        ['amixer', '-c', self.volume_card_num, 'sset', self.volume_control_name, f'{original_volume}%'],
                        capture_output=True, timeout=2
                    )
                    logger.debug(f"🔊 Volume restored: {temp_volume}% -> {original_volume}%")
                except Exception as e:
                    logger.debug(f"⚠️ Could not restore volume: {e}")

            # Always release lock
            self._lock.release()

    async def play_async(self, sound_type: str, volume: int = None):
        """
        Asynchronously plays a sound without blocking the event loop.

        Args:
            sound_type: Type of sound to play (start, stop, alert, etc.)
            volume: Temporary volume for this sound (None = use current, 200 = 200% for UI sounds)
        """
        file_path = self.sound_paths.get(sound_type)
        if not file_path:
            logger.error(f"❌ Sound type '{sound_type}' not found in config. Available types: {list(self.sound_paths.keys())}")
            return

        # Check if file exists
        if not os.path.exists(file_path):
            logger.error(f"❌ Sound file not found: {file_path}")
            logger.error(f"❌ Current working directory: {os.getcwd()}")
            logger.error(f"❌ Absolute path check: {os.path.abspath(file_path)}")
            return

        logger.info(f"🔊 Playing sound: {sound_type} -> {file_path} (exists: {os.path.exists(file_path)})")
        try:
            # Use dedicated audio executor for faster response
            loop = asyncio.get_event_loop()
            await loop.run_in_executor(self._audio_executor, self._blocking_play_task, file_path, volume)
            logger.info(f"✅ Sound playback completed: {sound_type}")
        except Exception as e:
            logger.error(f"❌ Error playing sound {sound_type}: {e}", exc_info=True)
    
    async def play_file_async(self, file_path: str):
        """
        Asynchronously plays an audio file from a file path without blocking the event loop.
        Useful for playing temporary TTS files.
        """
        if not file_path or not os.path.exists(file_path):
            logger.error(f"❌ Audio file not found: {file_path}")
            return
        
        # Use dedicated audio executor for faster response
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(self._audio_executor, self._blocking_play_task, file_path)
    
    def __del__(self):
        """Cleanup executor on destruction"""
        if hasattr(self, '_audio_executor'):
            self._audio_executor.shutdown(wait=False)
    
    def _detect_volume_control(self):
        """Dò tìm volume control name một lần khi khởi tạo (cache lại để dùng sau)"""
        if not self.alsa_device or "hw:" not in self.alsa_device:
            logger.debug("⚠️ Không thể dò volume control: ALSA device không hợp lệ")
            return
        
        # Trích xuất số card từ chuỗi "hw:1,0"
        self.volume_card_num = self.alsa_device.split(':')[1].split(',')[0]
        
        # Thử các control names theo thứ tự ưu tiên (WM8960 thường dùng 'Speaker Playback')
        control_names = ['Speaker Playback', 'Speaker', 'Playback', 'Master']
        
        for control_name in control_names:
            try:
                # Chỉ kiểm tra xem control có tồn tại không (không set volume)
                result = subprocess.run(
                    ['amixer', '-c', self.volume_card_num, 'sget', control_name],
                    check=True,
                    capture_output=True,
                    text=True,
                    timeout=2
                )
                # Nếu thành công, cache lại control name này
                self.volume_control_name = control_name
                logger.info(f"✅ Đã dò tìm volume control: Card {self.volume_card_num} -> '{control_name}'")
                return
            except (subprocess.CalledProcessError, TimeoutError, FileNotFoundError):
                # Thử control name tiếp theo
                continue
            except Exception as e:
                logger.debug(f"⚠️ Thử control '{control_name}' thất bại: {e}")
                continue
        
        logger.warning(f"⚠️ Không tìm thấy volume control cho Card {self.volume_card_num}")
        self.volume_control_name = None
    
    async def set_system_volume_async(self, volume_percent: int):
        """Cập nhật âm lượng hệ thống sử dụng control name đã cache (async, không block event loop)"""
        if not self.volume_control_name or not self.volume_card_num:
            logger.debug("⚠️ Không thể đặt âm lượng: chưa dò được volume control")
            return False
        
        try:
            process = await asyncio.create_subprocess_exec(
                'amixer', '-c', self.volume_card_num, 'sset', self.volume_control_name, f'{volume_percent}%',
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE
            )
            try:
                stdout, stderr = await asyncio.wait_for(process.communicate(), timeout=2.0)
                if process.returncode == 0:
                    logger.info(f"✅ Đã đặt âm lượng Card {self.volume_card_num} ({self.volume_control_name}) thành {volume_percent}%")
                    return True
                else:
                    stderr_text = stderr.decode('utf-8', errors='ignore')[:200] if stderr else ''
                    logger.error(f"❌ Không thể đặt âm lượng: {stderr_text}")
                    return False
            except asyncio.TimeoutError:
                process.kill()
                await process.wait()
                logger.error("❌ Đặt âm lượng timeout")
                return False
        except Exception as e:
            logger.error(f"❌ Lỗi khi đặt âm lượng: {e}")
            return False
    
    def set_system_volume(self, volume_percent: int):
        """Cập nhật âm lượng hệ thống sử dụng control name đã cache (sync wrapper, for backward compatibility)"""
        # For backward compatibility, run async version
        # This should only be called from sync context (e.g., during init)
        try:
            loop = asyncio.get_event_loop()
            # If we're in an async context, we can't use run_until_complete
            # This method should only be called from sync context
            if loop.is_running():
                # If loop is running, we're in async context - this shouldn't happen
                logger.warning("set_system_volume called from async context - use set_system_volume_async instead")
                return False
            return loop.run_until_complete(self.set_system_volume_async(volume_percent))
        except RuntimeError:
            # No event loop, create one
            return asyncio.run(self.set_system_volume_async(volume_percent))

    def _sanitize_file_path(self, file_path: str) -> Optional[str]:
        """
        Sanitize file path to prevent command injection.

        Args:
            file_path: Path to sanitize

        Returns:
            Sanitized absolute path, or None if invalid
        """
        if not file_path:
            return None

        try:
            # Convert to absolute path
            abs_path = os.path.abspath(file_path)

            # Check if path exists and is a file
            if not os.path.exists(abs_path):
                logger.error(f"❌ File does not exist: {abs_path}")
                return None

            if not os.path.isfile(abs_path):
                logger.error(f"❌ Path is not a file: {abs_path}")
                return None

            # Check for suspicious characters that might indicate command injection
            # Allow: alphanumeric, underscore, hyphen, dot, slash, colon, space, at-sign, percent
            # Reject: semicolon, pipe, ampersand, backtick, dollar, newline, carriage return
            suspicious_chars = [';', '|', '&', '`', '$', '\n', '\r', '\x00']
            path_lower = abs_path.lower()
            for char in suspicious_chars:
                if char in abs_path:
                    logger.error(f"❌ Suspicious character in file path: {char}")
                    return None

            # Additional check: reject paths with parent directory traversal attempts
            if '../' in abs_path or '..\\' in abs_path:
                logger.error(f"❌ Parent directory traversal not allowed: {abs_path}")
                return None

            return abs_path

        except Exception as e:
            logger.error(f"❌ Error sanitizing file path: {e}")
            return None
