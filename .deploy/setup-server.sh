#!/bin/bash
set -e

echo "=== Установка Docker ==="
curl -fsSL https://get.docker.com -o get-docker.sh
sh get-docker.sh

echo "=== Создание пользователя deployer ==="
useradd -m -s /bin/bash deployer 2>/dev/null || true
usermod -aG docker deployer

echo "=== SSH ключи для GitHub Actions ==="
su - deployer -c "
  mkdir -p ~/.ssh
  ssh-keygen -t ed25519 -C 'github-actions' -f ~/.ssh/github_actions -N ''
  cat ~/.ssh/github_actions.pub >> ~/.ssh/authorized_keys
  chmod 600 ~/.ssh/authorized_keys
"

echo "=== Создание директорий ==="
mkdir -p /opt/developer
mkdir -p /opt/developer/opencode-config/agents
chown -R deployer:deployer /opt/developer

echo "=== Настройка файрвола ==="
ufw allow 22/tcp 2>/dev/null || true
ufw allow 8080/tcp 2>/dev/null || true
ufw allow 8081/tcp 2>/dev/null || true
ufw --force enable 2>/dev/null || true

echo ""
echo "========================================"
echo "✅ Настройка сервера завершена!"
echo ""
echo "📋 ПРИВАТНЫЙ КЛЮЧ для GitHub Secrets (VPS_SSH_KEY):"
echo "========================================"
cat /home/deployer/.ssh/github_actions
echo ""
echo "========================================"
echo "Добавь этот ключ в GitHub Secrets как VPS_SSH_KEY"
echo "Также создай файл /opt/developer/.env с переменными окружения"
echo "========================================"
