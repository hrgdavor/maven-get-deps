# 🕯️ Quest 2: The Sentinel's Ritual - Conjuring the Systemd Spirits

Master Architect, you have learned to forge. Now, you must learn to **Bind**. An application that exists only while you watch it is a flickering candle. To make it an eternal flame, you must summon a **Systemd Sentinel**.

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

## 🏆 Rewards
- **Knowledge**: Service Users, Systemd Unit Files, ACL Permission Warding.
- **Exp**: +1500 Security Alchemy.

**Next Step**: Return to the **[Telekinetic Nexus](./README.md)** and prepare for the final trial: **The Chrono-Shifter's Leap**.
