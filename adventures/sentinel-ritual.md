# 🕯️ Adventure: The Sentinel's Ritual - Conjuring the Systemd Spirits

Welcome, Traveler! Your duty is to guard the stability and security of the realm. You have learned how artifacts are forged; now, you must seek the wisdom of the **Warden**.

> [!IMPORTANT]
> **Requirements**: 🛠️ Architect's Hammer, [Scroll of Systemd Binding](./primers/systemd.md).

> [!NOTE]
> This adventure is a narrative companion to the **[Systemd Guide](../doc/README.systemd.md)**.

## 💎 Rewards Summary
- **Seal of Mastery**: 🛡️ Seal of the Warden.
- **Loot Gained**: 💎 Crystalline Sentinel (Gem).
- **Primary Loot**: Systemd Unit File patterns, User Isolation rituals.
- **Secondary Loot**: ACL Permission Warding, Sentinel monitoring.
- **Experience**: +1500 Security Alchemy.



---

## 👥 Stage 1: The Circle of Isolation
*Goal: Create a dedicated spirit (User) to inhabit the app.*

Running an application as a "Root Overlord" is a dangerous path that leads to corruption. We must create a restricted user.

**Your Task**: Create a system user named `sentinel-app` and a group for shared access.
```bash
sudo useradd -r -s /bin/false sentinel-app
sudo groupadd devs
sudo usermod -aG devs sentinel-app
```
*You have created a vessel that can hold power without endangering the entire kingdom.*

---

## 🏹 Stage 2: Binding the Sentinel (Systemd)
*Goal: Create a ritual (Unit File) to keeping the app alive.*

The Sentinel needs instructions on how to behave.

**Your Task**: Create a service file `/etc/systemd/system/sentinel-app.service`.
```ini
[Unit]
Description=The Sentinel Application
After=network.target

[Service]
User=sentinel-app
Group=devs
WorkingDirectory=/opt/nexus-app
ExecStart=/usr/bin/java -jar /opt/nexus-app/current/nexus-app.jar
Restart=always

[Install]
WantedBy=multi-user.target
```
*Note the `User` and `Group` fields. They ensure the Sentinel acts within its boundaries.*

---

## 🛡️ Stage 3: The Warding of Paths (ACLs)
*Goal: Grant permissions without weakening the gates.*

Sometimes, your app needs to read resources owned by others. Instead of opening the gates to everyone (`chmod 777`), we use **Access Control Lists (ACLs)**.

**Your Task**: Grant the `sentinel-app` read access to a specific shared log folder.
```bash
# Ensure ACL tools are installed
sudo apt-get install acl

# Grant access just to our sentinel user
sudo setfacl -m u:sentinel-app:rx /var/log/shared-resources
```
*Verify with `getfacl /var/log/shared-resources`. You will see the individual user has been granted entry.*

---

## 🧪 Stage 4: Igniting the Flame
*Goal: Activate the ritual.*

**Your Task**: Tell the kernel to recognize your new service and start it.
```bash
sudo systemctl daemon-reload
sudo systemctl start sentinel-app
sudo systemctl enable sentinel-app
```
*Check the status: `sudo systemctl status sentinel-app`. If it is "Active", your Sentinel is now on duty!*

---

**Next Step**: Return to the **[Telekinetic Nexus](./README.md)** or proceed to **[The Chrono-Shifter's Leap](./chrono-shifter.md)**.


