# Hetzner VPS Deployment Plan

This plan hosts the existing Java/Spring Boot Telegram bot on a Hetzner VPS without rewriting the app.

## 1. Choose Server

Recommended minimum:

- Hetzner Cloud VPS with Ubuntu 24.04
- 2 GB RAM if possible
- 1 vCPU is enough for this bot
- Location: Germany or Finland

Avoid 512 MB RAM for this project. Spring Boot can run tight there and may fail during builds.

## 2. Create VPS

1. Log in to Hetzner Cloud Console.
2. Create a new project.
3. Create a server:
   - Image: Ubuntu 24.04
   - Type: smallest 2 GB RAM instance available
   - SSH key: add your local public key
   - Firewall: allow SSH only for now
4. Copy the server IP address.

## 3. Connect To Server

From your laptop:

```bash
ssh root@YOUR_SERVER_IP
```

Update packages:

```bash
apt update
apt upgrade -y
```

## 4. Install Docker

```bash
apt install -y ca-certificates curl git
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "${UBUNTU_CODENAME:-$VERSION_CODENAME}") stable" > /etc/apt/sources.list.d/docker.list
apt update
apt install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
```

Verify:

```bash
docker --version
docker compose version
```

## 5. Create App Directory

```bash
mkdir -p /opt/blackbox
cd /opt/blackbox
```

## 6. Copy Project To Server

Option A, from your laptop using `scp`:

```bash
scp -r /Users/dcokara/IdeaProjects/BlackBox/* root@YOUR_SERVER_IP:/opt/blackbox/
```

Option B, if the repo is on GitHub:

```bash
git clone YOUR_REPO_URL /opt/blackbox
cd /opt/blackbox
```

Do not commit or upload `.env` to GitHub.

## 7. Add Production `.env`

On the VPS:

```bash
cd /opt/blackbox
nano .env
```

Use this shape:

```env
TELEGRAM_RIO_BOT_TOKEN=your_rio_bot_token
TOMTOM_API_KEY=your_tomtom_api_key
SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/projecta
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=123

JAVA_OPTS=-Xms128m -Xmx700m
```

## 8. Configure Docker Compose

The current app runs as a single bot service.

Target compose shape:

```yaml
services:
  bot:
    build: .
    image: blackbox:latest
    container_name: blackbox
    restart: unless-stopped
    environment:
      TELEGRAM_RIO_BOT_TOKEN: ${TELEGRAM_RIO_BOT_TOKEN}
      TOMTOM_API_KEY: ${TOMTOM_API_KEY}
      SPRING_DATASOURCE_URL: ${SPRING_DATASOURCE_URL}
      SPRING_DATASOURCE_USERNAME: ${SPRING_DATASOURCE_USERNAME}
      SPRING_DATASOURCE_PASSWORD: ${SPRING_DATASOURCE_PASSWORD}
      JAVA_OPTS: ${JAVA_OPTS}
    ports:
      - "8100:8100"
```

The bot uses Telegram long polling, so it does not need inbound HTTP traffic to receive messages. Port `8100` is only useful if you want to call your local controller endpoints.

## 9. Start The Bot

```bash
cd /opt/blackbox
docker compose up -d --build
```

Check status:

```bash
docker compose ps
```

Check logs:

```bash
docker compose logs -f bot
```

## 10. Test Telegram Commands

In Telegram, test:

```text
/help
/title
/herbs
/ores
```

If commands do not respond, check:

```bash
docker compose logs --tail=200 bot
```

Common issues:

- Wrong bot token
- Database password mismatch
- Blizzard credentials invalid
- Not enough memory during Docker build

## 11. Firewall

For Telegram long polling, inbound bot traffic is not required.

Recommended Hetzner firewall:

- Allow TCP `22` from your IP only
- Optional: allow TCP `8100` only from your IP if you need app HTTP endpoints
- Block everything else inbound

Outbound internet must remain allowed.

## 12. Updates

If using `scp`, copy updated files again, then:

```bash
cd /opt/blackbox
docker compose up -d --build
docker compose logs -f bot
```

If using Git:

```bash
cd /opt/blackbox
git pull
docker compose up -d --build
docker compose logs -f bot
```

## 13. Recommended Next Improvements

- Add `restart: unless-stopped` to all services.
- Lower production logging from `DEBUG` to `INFO`.
- Keep `.env` out of Git.
- Add a simple deploy script later.
