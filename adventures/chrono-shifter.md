# 🌀 Adventure: The Chrono-Shifter's Leap - Masters of the Symlink Swap

Welcome, Traveler! Your domain is time and its flawless transitions. You have bound sentinels to the realm; now, you must master the trial of the **Gatekeeper**.

> [!IMPORTANT]
> **Requirements**: 💎 Crystalline Sentinel, [Tome of Temporal Shifting](./primers/chrono.md).

> [!NOTE]
> This adventure is a narrative companion to the **[Deployment Guide](../doc/README.usage-deploy.md)**.

## 💎 Rewards Summary
- **Seal of Mastery**: 🌀 Seal of the Gatekeeper.
- **Weapons Gained**: 🗡️ Chrono-Key (Sword).
- **Primary Loot**: `version_manager`, Atomic Swap protocols.
- **Secondary Loot**: `gen_index`, "Developer's Blink" (Rsync) shortcuts.
- **Experience**: +2500 Temporal Logistics.



---

## 📅 Stage 1: The Timeline Structure
*Goal: Organize your vault into temporal layers.*

We don't overwrite the old with the new. We keep each version in its own sanctuary.

**Your Task**: Create a versioned directory structure for your application.
```bash
sudo mkdir -p /opt/nexus-app/versions
sudo mkdir -p /opt/nexus-app/versions/v1.0.0
sudo mkdir -p /opt/nexus-app/versions/v1.1.0
```
*By keeping versions separate, you can always retreat to the past if the future is broken.*

---

## ⚡ Stage 2: The Atomic Swap (version_manager)
*Goal: Move between versions instantly.*

Using a symbolic link (`symlink`) allows your Systemd Sentinel to always point to `/opt/app/current`, while `current` itself changes destination. We first need a **Manifest** to track our timeline.

**Your Task**: Initialize the tracker and perform an atomic switch to `v1.1.0`.
```bash
# 1. Prepare a list of folders to scan for versions
echo "/opt/nexus-app/versions" > folders.txt

# 2. Generate the version index
gen_index --folders folders.txt --output versions.json

# 3. Initialize the Version Manager manifest
version_manager manifest.json init versions.json current

# 4. Deploy the new version
version_manager manifest.json deploy v1.1.0
```
*Observe with `ls -l /opt/nexus-app`. You will see `current` now points to `v1.1.0`. The change happened so fast that the application didn't even notice the ground shifted beneath it.*

---

## 📦 Stage 3: The CI Pipeline (Automated Tribute)
*Goal: Feed the vault from the heavens.*

In a productive empire, your CI (like GitHub Actions) should automatically populate the `versions` folder.

**Your Task**: Mock a CI deployment by rsyncing a build to the vault and updating the index.
```bash
# Simulating CI output to a versioned folder
rsync -avz ./dist/ user@server:/opt/nexus-app/versions/ci-build-${GITHUB_SHA}/

# Tell the server to re-scan for the new version
ssh user@server "gen_index --folders folders.txt && version_manager manifest.json deploy ci-build-${GITHUB_SHA}"
```
*Your CI should always push to a unique folder, then trigger the manager to swap the link.*

---

## 🚀 Stage 4: The Developer's Blink (Rapid Rsync)
*Goal: Transport your prototype to the server instantly.*

Sometimes, you need to test a small change without the full CI ritual. We use **Rsync** to only transport the differences.

**Your Task**: Synchronize your local dev folder to a special `dev` version slot.
```bash
# Rapidly transport only the changed bytes
rsync -avz --delete --exclude 'target' ./ user@server:/opt/nexus-app/versions/dev/

# Swap the Nexus to point to the dev version
ssh user@server "gen_index --folders folders.txt && version_manager manifest.json deploy dev --force"
```
*This is the "Developer's Blink". You can see your local changes live on the server in seconds.*

---

## 🏆 Final Ritual: Master of the Chronic Shift
*Goal: Rollback to the safe harbor.*

The ultimate test of a Chrono-Shifter is the **Rollback**.

**Your Task**: Disaster has struck `dev`! Leap back to `v1.1.0` instantly.
```bash
version_manager manifest.json deploy v1.1.0
```
*The Sentinel is once again secure. You have saved the realm.*

---

## 🎓 Graduation
You have completed the Trilogy!

**Next Step**: Visit the **[Telekinetic Nexus](./README.md)** to explore other paths or the **[Tome of Knowledge](../README.md)** to start your own empire.


