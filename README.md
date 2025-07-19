# **Minecraft Anti-Duplication** 🚀

🛠️ **A robust plugin to prevent item duplication on Minecraft Paper/Spigot 1.8+ servers**

---

## 📌 Features

✅ **Unique UUIDs** – Every item gets a unique identifier stored in NBT  
✅ **Automatic detection** – Periodic intelligent scans for duplicated items  
✅ **Instant removal** – Automatic deletion of duplicated items  
✅ **Comprehensive logging** – Structured JSON logs for full traceability  
✅ **Optimized performance** – Asynchronous architecture with no server impact  
✅ **Strict mode** – Enhanced checks against duplication exploits  
✅ **Creative monitoring** – Tracks actions performed in Creative Mode

---

## **📦 Installation**

### **1️⃣ Download the latest version [here](https://github.com/GaetanOff/AntiDuplication/releases)**

### **2️⃣ Place the JAR file in your `plugins/` folder**

### **3️⃣ Restart your server**

### **4️⃣ Configure the plugin via `plugins/AntiDuplication/config.yml`**

---

## **⚙️ How It Works**

* **UUID System**: Each item gets a unique identifier stored in its NBT data
* **Smart Scanning**: Periodic inventory checks to detect duplicated items
* **Performance Management**:

    * Asynchronous processing
    * Intelligent caching
    * Batch handling
* **Customization**:

    * Scan interval
    * Batch size
    * Logging level
    * Strict mode

---

## **📂 Project Structure**

```
AntiDuplication/
│── core/                    # Main business logic
│    ├── NBTUtils            # NBT UUID management
│    ├── ItemManager         # Item tracking
│    └── DuplicationDetector # Detection algorithms
│── listeners/               # Minecraft event handling
│── commands/                # Admin commands
│── config/                  # Plugin configuration
│── threading/               # Thread management
│── storage/                 # JSON logging system
│── model/                   # Data models
│── utils/                   # Utility classes
└── build.gradle             # Gradle build script
```

---

## **📝 Customization**

Configure the plugin via `config.yml`:

* **Scan intervals**: Adjust how often inventories are scanned
* **Batch size**: Optimize scan performance
* **Logging**: Enable/disable logging or set log level
* **Security**: Enable strict mode and Creative monitoring

For developers:

* **NBTUtils** – Customize UUID handling
* **DuplicationDetector** – Implement your own detection logic
* **JsonLogger** – Modify log output format

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
