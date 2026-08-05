# **Minecraft Anti-Duplication** 🚀

🛠️ **A robust plugin to prevent item duplication on Minecraft Paper/Spigot 1.8+ servers**

---

## 📌 Features

✅ **Unique UUIDs** – Every tracked item gets a unique identifier stored in its item data  
✅ **Automatic detection** – Periodic scans of players, containers and dropped items  
✅ **Safe removal** – Extra copies are deleted, the original is kept  
✅ **Comprehensive logging** – Newline-delimited JSON logs for full traceability  
✅ **Optimized performance** – Bounded main-thread work per tick, analysis off-thread  
✅ **Strict mode** – Oversized stacks trimmed, suspicious actions logged  
✅ **Creative monitoring** – Tracks item creation in Creative Mode and via commands  
✅ **Cross-server detection** – Optional Redis registry shared across a network  

---

## **📦 Installation**

### **1️⃣ Download the latest version [here](https://github.com/GaetanOff/AntiDuplication/releases)**

### **2️⃣ Place the JAR file in your `plugins/` folder**

### **3️⃣ Restart your server**

### **4️⃣ Configure the plugin via `plugins/AntiDuplication/config.yml`**

Nothing else is required. Redis is optional and disabled by default.

---

## **⚙️ How It Works**

### The UUID system

Each tracked item carries a short unique identifier in its item data. The same
identifier appearing in two places at once is a duplication.

Where that identifier is stored depends on the server, and is resolved at
startup — one jar runs everywhere:

| Server version | Storage |
|---|---|
| 1.14 and later | `PersistentDataContainer` (pure Bukkit API) |
| 1.8 to 1.13 | Item NBT, through reflection |

`/antidupe version` reports which backend is in use.

### What gets tracked, and why not everything

**Only non-stackable items are tracked by default** — tools, armour, enchanted
books, and everything else with a maximum stack size of one.

This is a deliberate design decision, not a limitation. The identifier lives in
the item data, and Minecraft only merges two stacks whose data is identical. A
unique identifier on a stackable item therefore stops it from stacking at all:
dirt you pick up would no longer join the dirt already in your inventory. That
breaks normal play far more than the duplication it would catch, and
non-stackable items are what duplication exploits actually target.

`security.track-stackable-items` turns tracking on for everything, and is
**experimental**. Splitting a stack copies its identifier, so every operation
that splits one has to be recognised or its pieces look duplicated — and a false
positive means a real item is deleted. Clicks, drags, partial pickups and hopper
transfers are recorded, and the pieces are accepted for `division-tolerance`
seconds up to `max-divisions-per-stack` pieces. Anything else that splits a stack
without passing through those events can still be misreported.

### The scan pipeline

A scan alternates threads on purpose:

1. **Sweep — main thread, sliced.** Item stacks are not thread safe, so they are
   only read where that is legal. The sweep is cut into units (one player, or one
   chunk with everything it holds) and only `scanning.batch-size` units run per
   tick, so the cost per tick stays bounded whatever the size of the world.
   Missing identifiers are stamped here, and oversized stacks are trimmed.
2. **Analysis — worker thread.** The sweep produces an immutable snapshot;
   grouping, severity, and the optional Redis round trips all happen off the
   server thread.
3. **Removal — main thread.** Every reported copy is re-checked against the live
   world before anything is deleted, then the extra copies are removed.

That re-check matters: because the sweep spans several ticks, an item carried
from an inventory into a chest in between is recorded twice. An item can only be
in one place at a time, so re-checking leaves only the copies that really exist.
Removal is also confined to the exact recorded slot — anything that moved is
skipped and picked up by the next scan, because destroying a legitimate item is
worse than catching a duplicate five seconds later.

### Two cadences

Players and chunks are swept on separate schedules, because they cost very
different amounts:

| Surface | Setting | Default | Why |
|---|---|---|---|
| Online players | `scanning.interval` | 5 s | Cheap, and where items change hands |
| Loaded chunks | `scanning.container-interval` | 60 s | Expensive and slow moving |

Walking every loaded chunk every five seconds does not finish in five seconds. On
a server with thousands of loaded chunks the sweep would still be running when the
next one came due, leaving the plugin permanently sweeping. `/antidupe scan`
always takes the full path.

### Coverage

Scanned: player inventories (storage, armour, off hand), the cursor, ender
chests, every container in loaded chunks, entity inventories (chest minecarts,
chested horses), the armour and weapon a mob or armour stand wears, items
displayed in item frames, and dropped items.

Not scanned: items nested inside a shulker box that is itself held in an
inventory, and offline players' inventories. Duplicates hidden there are not
detected — they are never falsely reported either, and they are picked up as soon
as the item comes back into view (a placed shulker box is a container like any
other).

Players holding `antiduplication.bypass` are skipped entirely: their items are
neither stamped, nor scanned, nor trimmed. Useful for a builder account in
Creative, where middle-click cloning legitimately produces two identical items.

---

## **🕹️ Commands**

All commands require the `antiduplication.admin` permission.

| Command | Description |
|---|---|
| `/antidupe scan` | Run a full scan now |
| `/antidupe scan <player>` | Scan a single player |
| `/antidupe stats` | Detection and performance statistics |
| `/antidupe check` | Tracking state of the item you are holding |
| `/antidupe reload` | Reload `config.yml` |
| `/antidupe version` | Plugin and backend information |
| `/antidupe debug <info\|report\|item>` | Diagnostics |
| `/antidupe help` | Command list |

### Permissions

| Permission | Default | Description |
|---|---|---|
| `antiduplication.admin` | OP | Commands and duplication alerts |
| `antiduplication.bypass` | none | Bypass checks (not recommended) |

---

## **📂 Project Structure**

```
AntiDuplication/
│── core/                     # Main business logic
│    ├── UUIDGenerator        # Identifier minting
│    ├── ItemManager          # Stamping, ownership registry, resolution
│    ├── StackManager         # What is trackable, what a legal stack is
│    ├── StackDivisionTracker # Legitimate stack splits
│    ├── InventoryScanner     # Sliced main-thread sweep
│    ├── ScanSnapshot         # Immutable sweep result
│    ├── DuplicationDetector  # Detection algorithms
│    ├── RetentionPolicy      # Which copy survives, and how severe it is
│    └── ItemRemover          # Verification and deletion
│── listeners/                # Minecraft event handling
│── commands/                 # Admin commands
│── config/                   # Plugin configuration
│── threading/                # Thread management and scheduling
│── storage/                  # JSON logging, optional Redis registry
│── model/                    # Data models
│── utils/                    # NBT abstraction and providers
└── build.gradle              # Gradle build script
```

---

## **📝 Customization**

Configure the plugin via `config.yml`; every option is documented in the file.
The settings worth knowing about:

* **`scanning.interval` / `container-interval` / `batch-size`** – how often each
  surface is swept, and how much main-thread work a tick may do
* **`security.track-stackable-items`** – tracking for stackable items, with the
  stacking trade-off described above
* **`security.block-no-uuid`** – destructive; deletes trackable items that carry
  no identifier instead of stamping them
* **`performance.cache-results`** – remembers which holder owns each identifier,
  which is what lets the plugin keep the original rather than an arbitrary copy
* **`redis.*`** – shared registry for a network of servers

For developers:

* **`NbtProvider`** – add a storage strategy for a server the plugin does not
  recognise
* **`DuplicationDetector`** – implement your own detection logic over a snapshot
* **`JsonLogger`** – modify the log output format

### Log format

Logs are newline-delimited JSON: one compact object per line, which keeps the
files appendable and streamable. One line per event:

```json
{"event_type":"item_duplication","uuid":"AD_XKPQ7RMTB2","duplicate_count":2,"severity":"LOW","action_taken":"ITEMS_REMOVED","copies_removed":1,"copies_skipped":0,"duplicated_items":[...]}
```

---

## **🔨 Building**

```bash
./gradlew build
```

The shaded jar lands in `build/libs/AntiDuplication-<version>.jar`. The output
targets Java 8 so it runs on legacy servers.

The wrapper pins Gradle 8.10, which supports JDK 8 through 22 — build with a JDK
in that range. Newer JDKs need a newer Gradle, and moving to Gradle 9 also means
moving to Shadow 9.x.

---

## **📝 License**

This project is licensed under [GPL v3](https://www.gnu.org/licenses/gpl-3.0.en.html).
Feel free to modify and improve it!

---

## **🙌 Contributing**

💡 **Got ideas, suggestions, or bug reports?**
Please open an **issue** or submit a **pull request** on GitHub!

---

## **📬 Contact**

📧 **contact@gaetandev.fr**  
🌍 **[Website](https://gaetandev.fr)**  
💬 **Discord: GaetanDev**  

---

## **🎉 Thank You for Using Anti-Duplication!**

If you have any questions or suggestions, feel free to reach out!
