import asyncio
import logging
import os
import shutil
import sys
import termios
import tty
from collections import deque
from typing import Callable, Optional

from src.display.base_display import BaseDisplay


class CliDisplay(BaseDisplay):
    def __init__(self):
        super().__init__()
        self.running = True
        self._use_ansi = sys.stdout.isatty()
        self._loop = None
        self._last_drawn_rows = 0

        # Try to use Whisplay HAT display if available
        self._whisplay_display = None
        self._init_whisplay_display()

        # Dashboard data (top content display area)
        self._dash_status = ""
        self._dash_connected = False
        self._dash_text = ""
        self._dash_emotion = ""
        # Layout: only two areas (display area + input area)
        # Reserve two lines for input area (separator line + input line), plus one extra line for Chinese input overflow cleanup
        self._input_area_lines = 3
        self._dashboard_lines = 8  # Minimum number of dashboard lines (will adjust dynamically with terminal height)

        # Colors/styles (only effective in TTY)
        self._ansi = {
            "reset": "\x1b[0m",
            "bold": "\x1b[1m",
            "dim": "\x1b[2m",
            "blue": "\x1b[34m",
            "cyan": "\x1b[36m",
            "green": "\x1b[32m",
            "yellow": "\x1b[33m",
            "magenta": "\x1b[35m",
        }

        # Callback functions
        self.auto_callback = None
        self.abort_callback = None
        self.send_text_callback = None
        self.mode_callback = None

        # Async queue for processing commands
        self.command_queue = asyncio.Queue()

        # Log buffer (only displayed at the top of CLI, not printed directly to console)
        self._log_lines: deque[str] = deque(maxlen=6)
        self._install_log_handler()

    def _init_whisplay_display(self):
        """Initialize Whisplay HAT display if available"""
        try:
            # Try to import WhisplayCliDisplay
            from src.display.whisplay_cli_display import WhisplayCliDisplay
            self._whisplay_display = WhisplayCliDisplay()
            self.logger.info("✅ Using Whisplay HAT display")
        except Exception as e:
            self.logger.debug(f"Whisplay HAT display not available: {e}")
            self._whisplay_display = None

    async def set_callbacks(
        self,
        press_callback: Optional[Callable] = None,
        release_callback: Optional[Callable] = None,
        mode_callback: Optional[Callable] = None,
        auto_callback: Optional[Callable] = None,
        abort_callback: Optional[Callable] = None,
        send_text_callback: Optional[Callable] = None,
    ):
        """
        Set callback functions.
        """
        self.auto_callback = auto_callback
        self.abort_callback = abort_callback
        self.send_text_callback = send_text_callback
        self.mode_callback = mode_callback

        # Forward to Whisplay display if available
        if self._whisplay_display:
            await self._whisplay_display.set_callbacks(
                press_callback=press_callback,
                release_callback=release_callback,
                mode_callback=mode_callback,
                auto_callback=auto_callback,
                abort_callback=abort_callback,
                send_text_callback=send_text_callback,
            )

    async def update_button_status(self, text: str):
        """
        Update button status.
        """
        # Forward to Whisplay display if available
        if self._whisplay_display:
            await self._whisplay_display.update_button_status(text)
        # Simplified: button status is only shown in dashboard text
        self._dash_text = text
        await self._render_dashboard()

    async def update_status(self, status: str, connected: bool):
        """
        Update status (only updates dashboard, does not append new line).
        """
        # Forward to Whisplay display if available
        if self._whisplay_display:
            await self._whisplay_display.update_status(status, connected)
        self._dash_status = status
        self._dash_connected = bool(connected)
        await self._render_dashboard()

    async def update_text(self, text: str):
        """
        Update text (only updates dashboard, does not append new line).
        """
        # Forward to Whisplay display if available
        if self._whisplay_display:
            await self._whisplay_display.update_text(text)
        if text and text.strip():
            self._dash_text = text.strip()
            await self._render_dashboard()

    async def update_emotion(self, emotion_name: str):
        """
        Update emotion (only updates dashboard, does not append new line).
        """
        # Forward to Whisplay display if available
        if self._whisplay_display:
            await self._whisplay_display.update_emotion(emotion_name)
        self._dash_emotion = emotion_name
        await self._render_dashboard()

    async def start(self):
        """
        Start async CLI display.
        """
        self.logger.info("🖥️ CLI Display starting...")
        self._loop = asyncio.get_running_loop()
        await self._init_screen()

        # Start command processor task
        self.logger.info("Creating command processor task...")
        command_task = asyncio.create_task(self._command_processor())
        self.logger.info("Creating keyboard input loop task...")
        input_task = asyncio.create_task(self._keyboard_input_loop())

        try:
            self.logger.info("Starting asyncio.gather on tasks...")
            await asyncio.gather(command_task, input_task)
        except KeyboardInterrupt:
            await self.close()
        except Exception as e:
            self.logger.error(f"Display start error: {e}", exc_info=True)
            await self.close()
        finally:
            self.logger.info("CLI Display start() completed")

    async def _command_processor(self):
        """
        Command processor.
        """
        while self.running:
            try:
                command = await asyncio.wait_for(self.command_queue.get(), timeout=1.0)
                if asyncio.iscoroutinefunction(command):
                    await command()
                else:
                    command()
            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                break
            except Exception as e:
                self.logger.error(f"Command processing error: {e}")

    async def _keyboard_input_loop(self):
        """
        Keyboard input loop.
        """
        self.logger.info(f"Keyboard input loop started, _use_ansi={self._use_ansi}, running={self.running}")
        try:
            while self.running:
                # In TTY, always use bottom input area to read input
                if self._use_ansi:
                    await self._render_input_area()
                    # Take over input (disable terminal echo), redraw input line char by char, fixing Chinese first-character residue issue
                    cmd = await asyncio.to_thread(self._read_line_raw)
                    # Clear input area (including possible Chinese line-break residue) and refresh dashboard
                    self._clear_input_area()
                    await self._render_dashboard()
                else:
                    # For headless/non-TTY mode, just wait and keep running
                    # Button/wake word will trigger actions via callbacks
                    self.logger.debug("Sleeping in headless mode...")
                    await asyncio.sleep(1)
                    continue
                await self._handle_command(cmd.lower().strip())
        except (asyncio.CancelledError, EOFError, KeyboardInterrupt) as e:
            self.logger.info(f"Keyboard input loop caught: {type(e).__name__}")
        except Exception as e:
            self.logger.error(f"Keyboard input loop error: {e}", exc_info=True)
        finally:
            self.logger.info("Keyboard input loop ended")

    # ===== Intercept logs and forward to display area =====
    def _install_log_handler(self) -> None:
        class _DisplayLogHandler(logging.Handler):
            def __init__(self, display: "CliDisplay"):
                super().__init__()
                self.display = display

            def emit(self, record: logging.LogRecord) -> None:
                try:
                    msg = self.format(record)
                    self.display._log_lines.append(msg)
                    # Also print to stderr for errors (ensures errors are always visible)
                    if record.levelno >= logging.ERROR:
                        sys.stderr.write(msg + "\n")
                        sys.stderr.flush()
                    loop = self.display._loop
                    if loop and self.display._use_ansi:
                        loop.call_soon_threadsafe(
                            lambda: asyncio.create_task(
                                self.display._render_dashboard()
                            )
                        )
                except Exception:
                    pass

        root = logging.getLogger()
        # Keep a stderr handler for errors/warnings even in dashboard mode
        # Only remove stdout handlers, keep stderr for critical messages
        for h in list(root.handlers):
            if isinstance(h, logging.StreamHandler) and getattr(h, "stream", None) is sys.stdout:
                root.removeHandler(h)

        handler = _DisplayLogHandler(self)
        handler.setLevel(logging.WARNING)
        handler.setFormatter(
            logging.Formatter(
                fmt="%(asctime)s [%(name)s] - %(levelname)s - %(message)s",
                datefmt="%Y-%m-%d %H:%M:%S",
            )
        )
        root.addHandler(handler)

    async def _handle_command(self, cmd: str):
        """
        Handle commands.
        """
        if cmd == "q":
            await self.close()
        elif cmd == "h":
            self._print_help()
        elif cmd == "r":
            if self.auto_callback:
                await self.command_queue.put(self.auto_callback)
        elif cmd == "x":
            if self.abort_callback:
                await self.command_queue.put(self.abort_callback)
        else:
            if self.send_text_callback:
                await self.send_text_callback(cmd)

    async def close(self):
        """
        Close CLI display.
        """
        self.running = False
        # Close Whisplay display if available
        if self._whisplay_display:
            await self._whisplay_display.close()
        print("\nClosing application...\n")

    def _print_help(self):
        """
        Write help info into dashboard instead of printing directly.
        """
        help_text = "r: start/stop | x: abort | q: quit | h: help | others: send text"
        self._dash_text = help_text

    async def _init_screen(self):
        """
        Initialize screen and render two areas (display area + input area).
        """
        # Skip console screen init if Whisplay display is available
        if self._whisplay_display:
            return

        if self._use_ansi:
            # Clear screen and move to top left
            sys.stdout.write("\x1b[2J\x1b[H")
            sys.stdout.flush()

        # Initial full render
        await self._render_dashboard(full=True)
        await self._render_input_area()

    def _goto(self, row: int, col: int = 1):
        sys.stdout.write(f"\x1b[{max(1,row)};{max(1,col)}H")

    def _term_size(self):
        try:
            size = shutil.get_terminal_size(fallback=(80, 24))
            return size.columns, size.lines
        except Exception:
            return 80, 24

    # ====== Raw input mode support, avoid Chinese residue ======
    def _read_line_raw(self) -> str:
        """
        Read a line using raw mode: disable echo, read char by char and re-echo manually.  
        Full line redraw avoids residue when deleting wide characters (Chinese).
        """
        fd = sys.stdin.fileno()
        old_settings = termios.tcgetattr(fd)
        try:
            tty.setraw(fd)
            buffer: list[str] = []
            while True:
                ch = os.read(fd, 4)  # Read up to 4 bytes, enough for common UTF-8 Chinese
                if not ch:
                    break
                try:
                    s = ch.decode("utf-8")
                except UnicodeDecodeError:
                    # If incomplete UTF-8, keep reading until valid
                    while True:
                        ch += os.read(fd, 1)
                        try:
                            s = ch.decode("utf-8")
                            break
                        except UnicodeDecodeError:
                            continue

                if s in ("\r", "\n"):
                    # Enter: newline, end input
                    sys.stdout.write("\r\n")
                    sys.stdout.flush()
                    break
                elif s in ("\x7f", "\b"):
                    # Backspace: delete one Unicode character
                    if buffer:
                        buffer.pop()
                    # Redraw full line to avoid residue from wide characters
                    self._redraw_input_line("".join(buffer))
                elif s == "\x03":  # Ctrl+C
                    raise KeyboardInterrupt
                else:
                    buffer.append(s)
                    self._redraw_input_line("".join(buffer))

            return "".join(buffer)
        finally:
            termios.tcsetattr(fd, termios.TCSADRAIN, old_settings)

    def _redraw_input_line(self, content: str) -> None:
        """
        Clear input line and rewrite current content, ensuring no residue from deleting wide characters.
        """
        cols, rows = self._term_size()
        separator_row = max(1, rows - self._input_area_lines + 1)
        first_input_row = min(rows, separator_row + 1)
        prompt = "Input: " if not self._use_ansi else "\x1b[1m\x1b[36mInput:\x1b[0m "
        self._goto(first_input_row, 1)
        sys.stdout.write("\x1b[2K")
        visible = content
        # Prevent wrapping if content exceeds one line
        max_len = max(1, cols - len("Input: ") - 1)
        if len(visible) > max_len:
            visible = visible[-max_len:]
        sys.stdout.write(f"{prompt}{visible}")
        sys.stdout.flush()

    async def _render_dashboard(self, full: bool = False):
        """
        Update dashboard content at top area without touching bottom input lines.
        """
        # Skip console dashboard rendering if Whisplay display is available
        if self._whisplay_display:
            # Only forward to Whisplay, don't render console dashboard
            return

        # Truncate long text to avoid line breaks tearing interface
        def trunc(s: str, limit: int = 80) -> str:
            return s if len(s) <= limit else s[: limit - 1] + "…"

        lines = [
            f"Status: {trunc(self._dash_status)}",
            f"Connection: {'Connected' if self._dash_connected else 'Disconnected'}",
            f"Emotion: {trunc(self._dash_emotion)}",
            f"Text: {trunc(self._dash_text)}",
        ]

        if not self._use_ansi:
            # Fallback: only print last status line
            print(f"\r{lines[0]}        ", end="", flush=True)
            return

        cols, rows = self._term_size()

        # Usable display rows = total terminal rows - input area lines
        usable_rows = max(5, rows - self._input_area_lines)

        # Small style helper
        def style(s: str, *names: str) -> str:
            if not self._use_ansi:
                return s
            prefix = "".join(self._ansi.get(n, "") for n in names)
            return f"{prefix}{s}{self._ansi['reset']}"

        title = style(" XiaoZhi AI Terminal ", "bold", "cyan")
        # Top and bottom frames
        top_bar = "┌" + ("─" * (max(2, cols - 2))) + "┐"
        title_line = "│" + title.center(max(2, cols - 2)) + "│"
        sep_line = "├" + ("─" * (max(2, cols - 2))) + "┤"
        bottom_bar = "└" + ("─" * (max(2, cols - 2))) + "┘"

        # Body rows (excluding 4 lines for frames)
        body_rows = max(1, usable_rows - 4)
        body = []
        for i in range(body_rows):
            text = lines[i] if i < len(lines) else ""
            text = style(text, "green") if i == 0 else text
            body.append("│" + text.ljust(max(2, cols - 2))[: max(2, cols - 2)] + "│")

        # Save cursor position
        sys.stdout.write("\x1b7")

        # Clear previous frame fully to avoid overlapping visuals
        total_rows = 4 + body_rows
        rows_to_clear = max(self._last_drawn_rows, total_rows)
        for i in range(rows_to_clear):
            self._goto(1 + i, 1)
            sys.stdout.write("\x1b[2K")

        # Draw top
        self._goto(1, 1)
        sys.stdout.write("\x1b[2K" + top_bar[:cols])
        self._goto(2, 1)
        sys.stdout.write("\x1b[2K" + title_line[:cols])
        self._goto(3, 1)
        sys.stdout.write("\x1b[2K" + sep_line[:cols])

        # Draw body
        for idx in range(body_rows):
            self._goto(4 + idx, 1)
            sys.stdout.write("\x1b[2K")
            sys.stdout.write(body[idx][:cols])

        # Draw bottom
        self._goto(4 + body_rows, 1)
        sys.stdout.write("\x1b[2K" + bottom_bar[:cols])

        # Restore cursor
        sys.stdout.write("\x1b8")
        sys.stdout.flush()

        # Record drawn rows
        self._last_drawn_rows = total_rows

    def _clear_input_area(self):
        if not self._use_ansi:
            return
        cols, rows = self._term_size()
        separator_row = max(1, rows - self._input_area_lines + 1)
        first_input_row = min(rows, separator_row + 1)
        second_input_row = min(rows, separator_row + 2)
        # Clear separator and two input rows to avoid residue from wide characters
        for r in [separator_row, first_input_row, second_input_row]:
            self._goto(r, 1)
            sys.stdout.write("\x1b[2K")
        sys.stdout.flush()

    async def _render_input_area(self):
        # Skip console input area rendering if Whisplay display is available
        if self._whisplay_display or not self._use_ansi:
            return
        cols, rows = self._term_size()
        separator_row = max(1, rows - self._input_area_lines + 1)
        first_input_row = min(rows, separator_row + 1)
        second_input_row = min(rows, separator_row + 2)

        # Save cursor
        sys.stdout.write("\x1b7")
        # Separator line
        self._goto(separator_row, 1)
        sys.stdout.write("\x1b[2K")
        sys.stdout.write("═" * max(1, cols))

        # Input prompt line
        self._goto(first_input_row, 1)
        sys.stdout.write("\x1b[2K")
        prompt = "Input: " if not self._use_ansi else "\x1b[1m\x1b[36mInput:\x1b[0m "
        sys.stdout.write(prompt)

        # Reserve overflow cleanup line
        self._goto(second_input_row, 1)
        sys.stdout.write("\x1b[2K")
        sys.stdout.flush()

        # Restore cursor then move to input position
        sys.stdout.write("\x1b8")
        self._goto(first_input_row, 1)
        sys.stdout.write(prompt)
        sys.stdout.flush()

    async def toggle_mode(self):
        """
        Mode toggle not supported in CLI mode
        """
        self.logger.debug("Mode toggle not supported in CLI mode")

    async def toggle_window_visibility(self):
        """
        Window visibility toggle not supported in CLI mode
        """
        self.logger.debug("Window visibility toggle not supported in CLI mode")
