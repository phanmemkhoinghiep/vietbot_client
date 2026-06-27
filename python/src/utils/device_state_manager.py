"""
Device State Manager with Timeout Mechanisms
Manages device state transitions with automatic timeout handling
"""
import asyncio
import time
from enum import Enum
from typing import Optional, Callable, Dict, Any
from dataclasses import dataclass

from src.constants.constants import DeviceState
from src.utils.logging_config import get_logger

logger = get_logger(__name__)


@dataclass
class StateTimeout:
    """State timeout configuration"""
    state: str
    timeout_seconds: float
    auto_transition: Optional[str] = None  # State to transition to on timeout
    on_timeout_callback: Optional[Callable] = None


class StateTransitionEvent(Enum):
    """State transition events"""
    ENTER = "enter"
    EXIT = "exit"
    TIMEOUT = "timeout"
    ERROR = "error"


@dataclass
class StateInfo:
    """State information"""
    name: str
    entered_at: float
    last_activity: float
    timeout_seconds: Optional[float] = None
    metadata: Optional[Dict[str, Any]] = None


class DeviceStateManager:
    """
    Device State Manager with timeout handling and automatic recovery

    Features:
    - State transition tracking
    - Automatic timeout detection and recovery
    - State transition callbacks
    - State history for debugging
    - Graceful error recovery
    """

    # Default timeout configurations (seconds)
    DEFAULT_TIMEOUTS = {
        DeviceState.CONNECTING: 30.0,    # 30 seconds to connect
        DeviceState.LISTENING: 60.0,    # 60 seconds of listening max
        DeviceState.SPEAKING: 120.0,    # 120 seconds of speaking max
    }

    # Recovery actions
    RECOVERY_ACTIONS = {
        DeviceState.CONNECTING: DeviceState.IDLE,
        DeviceState.LISTENING: DeviceState.IDLE,
        DeviceState.SPEAKING: DeviceState.IDLE,
    }

    def __init__(self, initial_state: str = DeviceState.IDLE):
        """
        Initialize device state manager

        Args:
            initial_state: Starting state
        """
        self._current_state = initial_state
        self._state_history = []
        self._state_info: Dict[str, StateInfo] = {}

        # State transition callbacks
        self._transition_callbacks: Dict[StateTransitionEvent, list] = {
            event: [] for event in StateTransitionEvent
        }

        # Timeout monitoring
        self._timeout_task: Optional[asyncio.Task] = None
        self._timeout_monitor_running = False

        # Custom timeouts
        self._custom_timeouts: Dict[str, StateTimeout] = {}

        # Lock for thread safety
        self._lock = asyncio.Lock()

        logger.info(f"🔧 DeviceStateManager initialized with state: {initial_state}")

    async def start(self):
        """Start state manager (begin timeout monitoring)"""
        if self._timeout_monitor_running:
            logger.warning("State manager already started")
            return

        self._timeout_monitor_running = True
        self._timeout_task = asyncio.create_task(self._timeout_monitor_loop())

        # Initialize first state
        await self._enter_state(self._current_state)

        logger.info("🔧 DeviceStateManager started")

    async def stop(self):
        """Stop state manager"""
        if not self._timeout_monitor_running:
            return

        self._timeout_monitor_running = False

        if self._timeout_task:
            self._timeout_task.cancel()
            try:
                await self._timeout_task
            except asyncio.CancelledError:
                pass
            self._timeout_task = None

        logger.info("🔧 DeviceStateManager stopped")

    async def transition_to(self, new_state: str, metadata: Optional[Dict[str, Any]] = None) -> bool:
        """
        Transition to a new state

        Args:
            new_state: Target state
            metadata: Optional metadata for the state

        Returns:
            True if transition successful
        """
        async with self._lock:
            if new_state == self._current_state:
                logger.debug(f"Already in state: {new_state}")
                return True

            # Validate state transition
            if not self._is_valid_transition(self._current_state, new_state):
                logger.warning(
                    f"Invalid state transition: {self._current_state} -> {new_state}"
                )
                return False

            # Exit current state
            await self._exit_state(self._current_state)

            # Add to history
            self._state_history.append({
                "from": self._current_state,
                "to": new_state,
                "timestamp": time.time(),
                "metadata": metadata
            })

            # Keep only last 100 transitions
            if len(self._state_history) > 100:
                self._state_history = self._state_history[-100:]

            # Enter new state
            self._current_state = new_state
            await self._enter_state(new_state, metadata)

            logger.info(f"🔄 State transition: {self._state_history[-1]['from']} -> {new_state}")
            return True

    def get_current_state(self) -> str:
        """Get current state"""
        return self._current_state

    def get_state_info(self, state: Optional[str] = None) -> Optional[StateInfo]:
        """Get state information"""
        if state is None:
            state = self._current_state
        return self._state_info.get(state)

    def is_in_state(self, state: str) -> bool:
        """Check if currently in given state"""
        return self._current_state == state

    def get_state_history(self, limit: int = 10) -> list:
        """Get recent state history"""
        return self._state_history[-limit:]

    def set_timeout(self, state: str, timeout_seconds: float, recovery_state: Optional[str] = None):
        """
        Set custom timeout for a state

        Args:
            state: State to set timeout for
            timeout_seconds: Timeout in seconds
            recovery_state: State to transition to on timeout (default: auto-detect)
        """
        if recovery_state is None:
            recovery_state = self.RECOVERY_ACTIONS.get(state)

        self._custom_timeouts[state] = StateTimeout(
            state=state,
            timeout_seconds=timeout_seconds,
            auto_transition=recovery_state
        )
        logger.info(f"⏱️ Set timeout for {state}: {timeout_seconds}s -> {recovery_state}")

    def on_transition(self, event: StateTransitionEvent, callback: Callable):
        """
        Register callback for state transition event

        Args:
            event: Event type
            callback: Callback function
        """
        self._transition_callbacks[event].append(callback)
        logger.debug(f"Registered callback for event: {event.value}")

    def _is_valid_transition(self, from_state: str, to_state: str) -> bool:
        """Check if state transition is valid"""
        # All states can transition to IDLE
        if to_state == DeviceState.IDLE:
            return True

        # IDLE can transition to any state
        if from_state == DeviceState.IDLE:
            return True

        # CONNECTING can transition to LISTENING or back to IDLE
        if from_state == DeviceState.CONNECTING:
            return to_state in [DeviceState.IDLE, DeviceState.LISTENING]

        # LISTENING can transition to SPEAKING or IDLE
        if from_state == DeviceState.LISTENING:
            return to_state in [DeviceState.IDLE, DeviceState.SPEAKING]

        # SPEAKING can transition to LISTENING or IDLE
        if from_state == DeviceState.SPEAKING:
            return to_state in [DeviceState.IDLE, DeviceState.LISTENING]

        return False

    async def _enter_state(self, state: str, metadata: Optional[Dict[str, Any]] = None):
        """Handle entering a state"""
        now = time.time()

        self._state_info[state] = StateInfo(
            name=state,
            entered_at=now,
            last_activity=now,
            timeout_seconds=self._get_timeout_for_state(state),
            metadata=metadata
        )

        # Call enter callbacks
        await self._notify_callbacks(StateTransitionEvent.ENTER, state, metadata)

    async def _exit_state(self, state: str):
        """Handle exiting a state"""
        # Call exit callbacks
        await self._notify_callbacks(StateTransitionEvent.EXIT, state)

        # Update last activity
        if state in self._state_info:
            self._state_info[state].last_activity = time.time()

    def _get_timeout_for_state(self, state: str) -> Optional[float]:
        """Get timeout for a state"""
        if state in self._custom_timeouts:
            return self._custom_timeouts[state].timeout_seconds
        return self.DEFAULT_TIMEOUTS.get(state)

    def _get_recovery_state(self, state: str) -> Optional[str]:
        """Get recovery state for timeout"""
        if state in self._custom_timeouts:
            return self._custom_timeouts[state].auto_transition
        return self.RECOVERY_ACTIONS.get(state)

    async def _timeout_monitor_loop(self):
        """Monitor state timeouts"""
        logger.debug("State timeout monitor started")

        while self._timeout_monitor_running:
            try:
                await asyncio.sleep(1.0)  # Check every second

                current_info = self._state_info.get(self._current_state)
                if not current_info or current_info.timeout_seconds is None:
                    continue

                # Check if timeout occurred
                elapsed = time.time() - current_info.entered_at
                if elapsed > current_info.timeout_seconds:
                    logger.warning(
                        f"⏱️ State timeout detected: {self._current_state} "
                        f"(elapsed: {elapsed:.1f}s, limit: {current_info.timeout_seconds}s)"
                    )

                    # Get recovery state
                    recovery_state = self._get_recovery_state(self._current_state)

                    if recovery_state:
                        logger.info(f"🔄 Auto-recovery: {self._current_state} -> {recovery_state}")

                        # Call timeout callbacks
                        await self._notify_callbacks(
                            StateTransitionEvent.TIMEOUT,
                            self._current_state,
                            {"recovery_state": recovery_state, "elapsed": elapsed}
                        )

                        # Transition to recovery state
                        await self.transition_to(recovery_state, {"reason": "timeout"})
                    else:
                        logger.error(f"❌ No recovery state for timeout: {self._current_state}")

                        # Call error callbacks
                        await self._notify_callbacks(
                            StateTransitionEvent.ERROR,
                            self._current_state,
                            {"reason": "timeout", "elapsed": elapsed}
                        )

            except asyncio.CancelledError:
                break
            except Exception as e:
                logger.error(f"Error in timeout monitor: {e}", exc_info=True)

        logger.debug("State timeout monitor stopped")

    async def _notify_callbacks(
        self,
        event: StateTransitionEvent,
        state: str,
        metadata: Optional[Dict[str, Any]] = None
    ):
        """Notify registered callbacks"""
        for callback in self._transition_callbacks[event]:
            try:
                if asyncio.iscoroutinefunction(callback):
                    await callback(state, metadata or {})
                else:
                    callback(state, metadata or {})
            except Exception as e:
                logger.error(f"Error in transition callback: {e}", exc_info=True)

    def get_status_dict(self) -> dict:
        """Get status as dictionary for monitoring"""
        current_info = self._state_info.get(self._current_state)

        return {
            "current_state": self._current_state,
            "state_entered_at": current_info.entered_at if current_info else None,
            "state_duration": time.time() - current_info.entered_at if current_info else 0,
            "state_timeout": current_info.timeout_seconds if current_info else None,
            "state_metadata": current_info.metadata if current_info else None,
            "recent_transitions": len(self._state_history),
        }
