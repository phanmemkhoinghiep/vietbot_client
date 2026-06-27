#!/bin/bash
# Deploy Xiaozhi Python Client v2 to Raspberry Pi

# CẤU HÌNH: ĐỔI IP ĐỊA CỦA RASPBERRY PI
PI_HOST="pi@whisplay-chatbot"  # Đổi thành IP của Pi nếu khác
PI_PATH="/home/pi/xiaozhi_python_client_v2"

echo "📦 Deploying to Raspberry Pi: $PI_HOST"
echo ""

# Test connection
echo "🔍 Testing connection..."
ssh -o ConnectTimeout=5 "$PI_HOST" "echo '✅ Connected to Pi'" || {
    echo "❌ Cannot connect. Check:"
    echo "   1. Pi is on and network is up"
    echo "   2. Update PI_HOST in this script"
    exit 1
}

# Copy main entry point
echo "📄 Copying main.py..."
rsync -avz --progress \
    /home/admin/xiaozhi/dev/xiaozhi_python_client_v2/main.py "$PI_HOST:$PI_PATH/"

# Copy config files
echo "⚙️ Copying config files..."
rsync -avz --progress \
    --include='*.json' \
    --include='*/' \
    --exclude='*.log' \
    /home/admin/xiaozhi/dev/xiaozhi_python_client_v2/ "$PI_HOST:$PI_PATH/" 2>/dev/null || true

# Copy updated files
echo "📤 Copying updated files..."
rsync -avz --progress \
    --include='*.py' \
    --include='*/' \
    --exclude='__pycache__' \
    --exclude='*.pyc' \
    --exclude='*.log' \
    /home/admin/xiaozhi/dev/xiaozhi_python_client_v2/src/ "$PI_HOST:$PI_PATH/src/"

# Copy libs directory (important!)
echo "📚 Copying libs directory..."
rsync -avz --progress \
    /home/admin/xiaozhi/dev/xiaozhi_python_client_v2/libs/ "$PI_HOST:$PI_PATH/libs/"

echo ""
echo "✅ Deploy complete!"
echo ""
echo "📋 Next steps on Pi:"
echo "   ssh $PI_HOST"
echo "   cd $PI_PATH"
echo "   python3 main.py"
