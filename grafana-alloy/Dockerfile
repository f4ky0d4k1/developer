FROM grafana/alloy:latest

# Копируем конфигурацию в контейнер
COPY config.alloy /etc/alloy/config.alloy

# Запускаем Alloy с нашим конфигом
ENTRYPOINT ["/bin/alloy", "run", "/etc/alloy/config.alloy"]
