class BaseDisplay:
    """Interface cho các màn hình hiển thị."""

    def show_ready(self, text: str = "Ready", emoji: str = "✅"):
        pass

    def show_pairing_code(self, code: str, message: str = ""):
        pass

    def show_error(self, text: str):
        pass

    def show_status(self, status: str, emoji: str = "", text: str = ""):
        pass

    def update(self, **kwargs):
        pass

