import sys
from pathlib import Path
from typing import List, Optional, Union

from src.utils.logging_config import get_logger

logger = get_logger(__name__)


class ResourceFinder:
    """
    Unified resource finder - Supports resource lookup in development environment, PyInstaller directory mode and single-file mode.
    """

    _instance = None
    _base_paths = None
    _app_name = None

    def __new__(cls):
        if cls._instance is None:
            cls._instance = super().__new__(cls)
        return cls._instance

    def __init__(self):
        """
        Initialize resource finder.
        """
        if self._base_paths is None:
            self._app_name = self._detect_app_name()
            self._base_paths = self._get_base_paths()
            logger.debug(
                f"Resource finder initialized, app name: {self._app_name}, base paths: {[str(p) for p in self._base_paths]}"
            )

    def _detect_app_name(self) -> str:
        """
        Dynamically detect app name, avoid hardcoding. Priority: environment variable > .app bundle name > executable name > project directory name > default value.
        """
        import os

        # 1. Environment variable specification
        env_name = os.getenv("APP_NAME") or os.getenv("XIAOZHI_APP_NAME")
        if env_name:
            logger.debug(f"Got app name from environment variable: {env_name}")
            return env_name.lower()

        # 2. macOS .app bundle name
        if sys.platform == "darwin" and getattr(sys, "frozen", False):
            exe_path = Path(sys.executable)
            for p in [exe_path] + list(exe_path.parents):
                if p.name.endswith(".app"):
                    app_name = p.name[:-4]  # Remove .app suffix
                    logger.debug(f"Got app name from .app bundle: {app_name}")
                    return app_name.lower()

        # 3. Executable name (remove extension)
        if getattr(sys, "frozen", False):
            exe_name = Path(sys.executable).stem
            if exe_name and exe_name != "python":
                logger.debug(f"Got app name from executable: {exe_name}")
                return exe_name.lower()

        # 4. Project directory name (development environment)
        if not getattr(sys, "frozen", False):
            project_root = Path(__file__).parent.parent.parent
            project_name = project_root.name
            if project_name and not project_name.startswith("."):
                # Smart handling of project name: py-xiaozhi -> xiaozhi, MyApp-iOS -> myapp
                app_name = project_name.lower()
                # Remove common prefixes
                for prefix in ["py-", "python-", "app-", "project-"]:
                    if app_name.startswith(prefix):
                        app_name = app_name[len(prefix) :]
                        break
                # Remove common suffixes
                for suffix in ["-app", "-project", "-ios", "-android", "-desktop"]:
                    if app_name.endswith(suffix):
                        app_name = app_name[: -len(suffix)]
                        break
                # Clean special characters
                app_name = app_name.replace("-", "").replace("_", "")
                logger.debug(f"Got app name from project directory: {project_name} -> {app_name}")
                return app_name

        # 5. Default value
        logger.debug("Using default app name: xiaozhi")
        return "xiaozhi"

    def _get_base_paths(self) -> List[Path]:
        """
        Get all possible base paths, priority order:
        1. Development environment: project root directory
        2. macOS .app bundle: Contents/Resources (PyInstaller --add-data target)
        3. Other PyInstaller standard paths
        4. Fallback paths: current working directory and its parent directories
        """
        base_paths = []

        logger.debug(
            f"Detecting runtime environment - frozen: {getattr(sys, 'frozen', False)}, platform: {sys.platform}"
        )

        # Development environment first
        if not getattr(sys, "frozen", False):
            project_root = Path(__file__).parent.parent.parent
            base_paths.append(project_root)
            logger.debug(f"Development environment - Project root: {project_root}")

            cwd = Path.cwd()
            if cwd != project_root:
                base_paths.append(cwd)
                logger.debug(f"Development environment - Current working directory: {cwd}")
            # Allow environment variable override (highest priority)
            self._add_env_paths(base_paths)
            return base_paths

        # === Packaged environment ===
        exe_path = Path(sys.executable).resolve()
        exe_dir = exe_path.parent
        logger.debug(f"Packaged environment - Executable path: {exe_path}")
        logger.debug(f"Packaged environment - Executable directory: {exe_dir}")

        # macOS .app Bundle support (highest priority)
        app_root = None
        if sys.platform == "darwin":
            # Look for directory ending with .app in exe path and its parent paths
            for p in [exe_path] + list(exe_path.parents):
                if p.name.endswith(".app"):
                    app_root = p
                    logger.debug(f"Found macOS .app bundle: {app_root}")
                    break

        if app_root is not None:
            # Contents/Resources - PyInstaller --add-data target location
            resources_dir = app_root / "Contents" / "Resources"
            if resources_dir.exists():
                base_paths.append(resources_dir)
                logger.debug(f"Added macOS Resources path: {resources_dir}")

            # Also check Frameworks directory, some resources may be here
            frameworks_dir = app_root / "Contents" / "Frameworks"
            if frameworks_dir.exists():
                base_paths.append(frameworks_dir)
                logger.debug(f"Added macOS Frameworks path: {frameworks_dir}")

            # Contents/MacOS - Executable directory (fallback)
            if exe_dir not in base_paths:
                base_paths.append(exe_dir)
                logger.debug(f"Added macOS MacOS directory: {exe_dir}")
        else:
            logger.debug("macOS .app bundle not found")

        # PyInstaller standard paths
        if exe_dir not in base_paths:
            base_paths.append(exe_dir)

        # PyInstaller _MEIPASS (single-file mode)
        if hasattr(sys, "_MEIPASS"):
            meipass_dir = Path(sys._MEIPASS)
            if meipass_dir not in base_paths:
                base_paths.append(meipass_dir)

        # PyInstaller _internal directory (6.0.0+)
        internal_dir = exe_dir / "_internal"
        if internal_dir.exists() and internal_dir not in base_paths:
            base_paths.append(internal_dir)

        # Standard installation path support (system-level installation)
        self._add_system_install_paths(base_paths, exe_path)

        # User config path (for writable configuration)
        self._add_user_config_paths(base_paths)

        # Environment variable specified paths
        self._add_env_paths(base_paths)

        # Fallback paths
        exe_parent = exe_dir.parent
        if exe_parent not in base_paths:
            base_paths.append(exe_parent)

        # Current working directory fallback (when starting from non-standard location in some cases)
        try:
            cwd = Path.cwd()
            if cwd not in base_paths:
                base_paths.append(cwd)
        except Exception:
            pass

        # Deduplicate but maintain order
        unique_paths = []
        seen = set()
        for p in base_paths:
            if p not in seen:
                unique_paths.append(p)
                seen.add(p)

        return unique_paths

    def _add_system_install_paths(self, base_paths: List[Path], exe_path: Path):
        """
        Add system-level installation paths.
        """
        if sys.platform == "darwin":
            # macOS standard paths
            candidates = [
                exe_path.parent
                / ".."
                / "share"
                / self._app_name,  # /usr/local/share/app_name
                exe_path.parent / ".." / "Resources",  # Relative Resources
                Path("/usr/local/share") / self._app_name,
                Path("/opt") / self._app_name,
            ]
        elif sys.platform.startswith("linux"):
            # Linux standard paths
            candidates = [
                exe_path.parent / ".." / "share" / self._app_name,
                Path("/usr/share") / self._app_name,
                Path("/usr/local/share") / self._app_name,
                Path("/opt") / self._app_name,
            ]
        else:
            # Windows
            candidates = [
                exe_path.parent / "data",
                Path("C:/ProgramData") / self._app_name,
            ]

        for candidate in candidates:
            try:
                if candidate.exists() and candidate not in base_paths:
                    base_paths.append(candidate.resolve())
            except (OSError, RuntimeError):
                pass  # Ignore invalid paths

    def _add_user_config_paths(self, base_paths: List[Path]):
        """
        Add user configuration paths.
        """
        home = Path.home()

        if sys.platform == "darwin":
            candidates = [
                home / "Library" / "Application Support" / self._app_name,
                home / ".config" / self._app_name,
            ]
        elif sys.platform.startswith("linux"):
            candidates = [
                home / ".config" / self._app_name,
                home / ".local" / "share" / self._app_name,
            ]
        else:
            candidates = [
                home / "AppData" / "Local" / self._app_name,
                home / "AppData" / "Roaming" / self._app_name,
            ]

        for candidate in candidates:
            try:
                if candidate.exists() and candidate not in base_paths:
                    base_paths.append(candidate)
            except (OSError, RuntimeError):
                pass

    def _add_env_paths(self, base_paths: List[Path]):
        """
        Add paths specified by environment variables.
        """
        import os

        app_name_upper = self._app_name.upper()
        env_vars = [
            f"{app_name_upper}_DATA_DIR",
            f"{app_name_upper}_HOME",
            f"{app_name_upper}_RESOURCES_DIR",
            # Compatible with old environment variables
            "XIAOZHI_DATA_DIR",
            "XIAOZHI_HOME",
            "XIAOZHI_RESOURCES_DIR",
        ]

        for env_var in env_vars:
            env_path = os.getenv(env_var)
            if env_path:
                try:
                    path = Path(env_path)
                    if path.exists() and path not in base_paths:
                        base_paths.insert(0, path)  # Environment variable has highest priority
                except (OSError, RuntimeError):
                    pass

    def find_resource(
        self, resource_path: Union[str, Path], resource_type: str = "file"
    ) -> Optional[Path]:
        """Find resource file or directory.

        Args:
            resource_path: Resource path relative to project root
            resource_type: Resource type, "file" or "dir"

        Returns:
            Found resource absolute path, None if not found
        """
        resource_path = Path(resource_path)
        logger.debug(f"Finding resource: {resource_path}, type: {resource_type}")

        # If already absolute path and exists, return directly
        if resource_path.is_absolute():
            if resource_type == "file" and resource_path.is_file():
                logger.debug(f"Found file using absolute path: {resource_path}")
                return resource_path
            elif resource_type == "dir" and resource_path.is_dir():
                logger.debug(f"Found directory using absolute path: {resource_path}")
                return resource_path
            else:
                logger.debug(f"Absolute path does not exist: {resource_path}")
                return None

        # Search in all base paths
        logger.debug(f"Searching for resource in {len(self._base_paths)} base paths")
        for i, base_path in enumerate(self._base_paths):
            full_path = base_path / resource_path
            logger.debug(f"Trying path {i+1}: {full_path}")

            if resource_type == "file" and full_path.is_file():
                logger.info(f"✓ Found file: {full_path}")
                return full_path
            elif resource_type == "dir" and full_path.is_dir():
                logger.info(f"✓ Found directory: {full_path}")
                return full_path

        logger.warning(f"✗ Resource not found: {resource_path}")
        logger.debug(f"Searched base paths: {[str(p) for p in self._base_paths]}")
        return None

    def find_file(self, file_path: Union[str, Path]) -> Optional[Path]:
        """Find file.

        Args:
            file_path: File path relative to project root

        Returns:
            Found file absolute path, None if not found
        """
        return self.find_resource(file_path, "file")

    def find_directory(self, dir_path: Union[str, Path]) -> Optional[Path]:
        """Find directory.

        Args:
            dir_path: Directory path relative to project root

        Returns:
            Found directory absolute path, None if not found
        """
        return self.find_resource(dir_path, "dir")

    def find_models_dir(self) -> Optional[Path]:
        """Find models directory.

        Returns:
            Found models directory absolute path, None if not found
        """
        return self.find_directory("models")

    def find_config_dir(self) -> Optional[Path]:
        """Find config directory.

        Returns:
            Found config directory absolute path, None if not found
        """
        return self.find_directory("config")

    def find_assets_dir(self) -> Optional[Path]:
        """Find assets directory.

        Returns:
            Found assets directory absolute path, None if not found
        """
        return self.find_directory("assets")

    def find_libs_dir(self, system: str = None, arch: str = None) -> Optional[Path]:
        """Find libs directory (for dynamic libraries)

        Args:
            system: System name (e.g. Windows, Linux, Darwin)
            arch: Architecture name (e.g. x64, x86, arm64)

        Returns:
            Found libs directory absolute path, None if not found
        """
        # Base libs directory
        libs_dir = self.find_directory("libs")

        # Fallback: if not found, try to find libs directory directly
        if not libs_dir:
            # Try to find libs directory relative to project root
            project_root = self.get_project_root()
            libs_fallback = project_root / "libs"
            if libs_fallback.is_dir():
                libs_dir = libs_fallback
                logger.debug(f"Found libs via fallback: {libs_dir}")
            else:
                # Try to find libs in current working directory
                cwd = Path.cwd()
                cwd_libs = cwd / "libs"
                if cwd_libs.is_dir():
                    libs_dir = cwd_libs
                    logger.debug(f"Found libs in cwd: {libs_dir}")

        if not libs_dir:
            return None

        # If system and architecture specified, find specific subdirectory
        if system and arch:
            specific_dir = libs_dir / system / arch
            if specific_dir.is_dir():
                return specific_dir
        elif system:
            system_dir = libs_dir / system
            if system_dir.is_dir():
                return system_dir

        return libs_dir

    def get_project_root(self) -> Path:
        """Get project root directory.

        Returns:
            Project root directory path
        """
        # Development environment: directly return source project root directory
        if not getattr(sys, "frozen", False):
            return Path(__file__).parent.parent.parent

        # Packaged environment: prioritize macOS .app's Resources, then single-file unpack directory, finally executable directory
        exe_path = Path(sys.executable).resolve()
        exe_dir = exe_path.parent

        if sys.platform == "darwin":
            for p in [exe_path] + list(exe_path.parents):
                if p.name.endswith(".app"):
                    resources_dir = p / "Contents" / "Resources"
                    if resources_dir.exists():
                        return resources_dir
                    break

        if hasattr(sys, "_MEIPASS"):
            try:
                meipass_dir = Path(sys._MEIPASS)
                if meipass_dir.exists():
                    return meipass_dir
            except Exception:
                pass

        return exe_dir

    def get_app_path(self) -> Path:
        """Get application base path (compatible with ConfigManager method)

        Returns:
            Application base path
        """
        if getattr(sys, "frozen", False) and hasattr(sys, "_MEIPASS"):
            # If running through PyInstaller package
            return Path(sys._MEIPASS)
        else:
            # If running in development environment
            return self.get_project_root()

    def get_user_data_dir(self, create: bool = True) -> Path:
        """Get user data directory for storing writable files.

        Args:
            create: Whether to create directory (if not exists)

        Returns:
            User data directory path
        """
        if sys.platform == "win32":  # Windows
            data_dir = Path.home() / "AppData" / "Local" / self._app_name
        elif sys.platform == "darwin":  # macOS
            data_dir = Path.home() / "Library" / "Application Support" / self._app_name
        else:  # Linux and others
            data_dir = Path.home() / ".local" / "share" / self._app_name

        if create:
            try:
                data_dir.mkdir(parents=True, exist_ok=True)
                logger.debug(f"User data directory: {data_dir}")
            except OSError as e:
                logger.error(f"Failed to create user data directory: {e}")
                # Fallback: use temporary directory
                import tempfile

                data_dir = Path(tempfile.gettempdir()) / self._app_name
                data_dir.mkdir(parents=True, exist_ok=True)
                logger.warning(f"Using temporary directory as user data directory: {data_dir}")

        return data_dir

    def get_user_cache_dir(self, create: bool = True) -> Path:
        """Get user cache directory.

        Args:
            create: Whether to create directory (if not exists)

        Returns:
            User cache directory path
        """
        cache_dir = self.get_user_data_dir(create=False) / "cache"

        if create:
            try:
                cache_dir.mkdir(parents=True, exist_ok=True)
                logger.debug(f"User cache directory: {cache_dir}")
            except OSError as e:
                logger.error(f"Failed to create user cache directory: {e}")

        return cache_dir

    def get_app_name(self) -> str:
        """Get application name.

        Returns:
            Detected application name
        """
        return self._app_name

    def list_files_in_directory(
        self, dir_path: Union[str, Path], pattern: str = "*"
    ) -> List[Path]:
        """List files in directory.

        Args:
            dir_path: Directory path
            pattern: File matching pattern

        Returns:
            List of file paths
        """
        directory = self.find_directory(dir_path)
        if not directory:
            return []

        try:
            return list(directory.glob(pattern))
        except Exception as e:
            logger.error(f"Error listing directory files: {e}")
            return []


# Global singleton instance
resource_finder = ResourceFinder()


# Convenience functions
def find_file(file_path: Union[str, Path]) -> Optional[Path]:
    """
    Convenience function to find file.
    """
    return resource_finder.find_file(file_path)


def find_directory(dir_path: Union[str, Path]) -> Optional[Path]:
    """
    Convenience function to find directory.
    """
    return resource_finder.find_directory(dir_path)


def find_models_dir() -> Optional[Path]:
    """
    Convenience function to find models directory.
    """
    return resource_finder.find_models_dir()


def find_config_dir() -> Optional[Path]:
    """
    Convenience function to find config directory.
    """
    return resource_finder.find_config_dir()


def find_assets_dir() -> Optional[Path]:
    """
    Convenience function to find assets directory.
    """
    return resource_finder.find_assets_dir()


def find_libs_dir(system: str = None, arch: str = None) -> Optional[Path]:
    """
    Convenience function to find libs directory.
    """
    return resource_finder.find_libs_dir(system, arch)


def get_project_root() -> Path:
    """
    Convenience function to get project root directory.
    """
    return resource_finder.get_project_root()


def get_app_path() -> Path:
    """
    Convenience function to get application base path.
    """
    return resource_finder.get_app_path()


def get_user_data_dir(create: bool = True) -> Path:
    """
    Convenience function to get user data directory.
    """
    return resource_finder.get_user_data_dir(create)


def get_user_cache_dir(create: bool = True) -> Path:
    """
    Convenience function to get user cache directory.
    """
    return resource_finder.get_user_cache_dir(create)


def get_app_name() -> str:
    """
    Convenience function to get application name.
    """
    return resource_finder.get_app_name()
