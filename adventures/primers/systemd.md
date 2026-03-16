# 🕯️ Scroll of Systemd Binding
*A Primer on Service Sentinels*

To perform the Sentinel's Ritual, you must understand how to bind a process to the Linux kernel.

## 📜 The Unit File
A `.service` file tells Systemd how to manage your app. Key sections:
- **[Unit]**: metadata and dependencies (e.g., `After=network.target`).
- **[Service]**: execution details (`ExecStart`), and isolation (`User`, `Group`).
- **[Install]**: when to start the app (e.g., `multi-user.target`).

## ⚔️ The Commands of Command
- `systemctl start`: Ignite the process.
- `systemctl stop`: Extinguish the process.
- `systemctl enable`: Ensure the process survives a reboot.
- `systemctl status`: Peer into the state of the sentinel.

## 🛡️ User Isolation
Always bind your apps to a restricted User. This prevents a compromised app from seizing control of the entire server.

*You are now prepared to begin the Ritual.*
