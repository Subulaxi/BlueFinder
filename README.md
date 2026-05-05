# BlueFinder  (蓝牙查找 / Bluetooth Radar)

>  蓝牙设备定位软件，便于查找身边的蓝牙设备。
>  Bluetooth device positioning software, convenient for finding nearby Bluetooth devices.

## 📖 简介 (Introduction)

**🇨🇳 **
BlueFinder 是一款使用 Kotlin 和 Jetpack Compose 构建的极简风格 Android 蓝牙雷达工具。它不仅支持标准的 BLE (低功耗蓝牙) 扫描，还针对传统经典蓝牙设备（如无线耳机）创新性地引入了“呼吸式狂暴刷新”防卡死机制，彻底解决了传统蓝牙 RSSI 信号刷新极慢的底层痛点。配合高级发光雷达动效和可动态调节的信号衰减算法，帮助你快速找回遗忘在角落的蓝牙设备。

**🇬🇧 **
BlueFinder is a minimalist Android Bluetooth radar tool built with Kotlin and Jetpack Compose. It not only supports standard BLE scanning but also introduces an innovative "breathing aggressive refresh" mechanism for Classic Bluetooth devices (like wireless earbuds). This completely solves the underlying issue of extremely slow RSSI signal updates in traditional Bluetooth. Combined with advanced glowing radar animations and dynamically adjustable signal attenuation algorithms, it helps you quickly track down your lost Bluetooth devices.

## ✨ 核心特性 (Features)

🇨🇳 **中文：**
* 🚀 **双擎扫描 (Dual-Engine)**: 完美兼容 BLE 与经典蓝牙设备，天线隔离设计，防止经典扫描抢占 BLE 资源。
* ⚡ **防卡死高频刷新 (Anti-Freeze Refresh)**: 包含节流逻辑，强制高频刷新经典蓝牙 RSSI 信号，既快又稳，保护底层硬件。
* 🎛️ **支持参数微调 (Precision Tuning)**: 内置全局设置面板，自由调节环境衰减因子 (n)（涵盖开阔到复杂环境）与“十分接近”的颜色触发阈值。
* 🎨 **极简质感 UI (Minimalist UI)**: 原生 Material Design 3 风格，纯黑夜间模式，搭载呼吸式动态发光雷达动效与丝滑转场。

🇬🇧 **English:**
* 🚀 **Dual-Engine Scanning**: Fully compatible with both BLE and Classic Bluetooth devices. Features antenna isolation to prevent Classic scanning from hogging BLE resources.
* ⚡ **Anti-Freeze High-Frequency Refresh**: An exclusive 1-second throttled logic that forces high-frequency updates for Classic Bluetooth RSSI, ensuring fast, stable feedback while protecting underlying hardware.
* 🎛️ **Precision Tuning**: Built-in global settings panel to freely adjust the environmental attenuation factor (n) (from open spaces to complex environments) and the color-changing "Very Close" threshold.
* 🎨 **Minimalist UI**: Native Material Design 3 styling, pure dark mode, featuring a breathing dynamic glowing radar animation and smooth transitions.
* ⚙️ **Smart Connect**: Quick guidance from the scan card to system settings for pairing, establishing a GATT channel for extreme-frequency signal feedback.

## 🛠️ 技术栈 (Tech Stack)

* **Language**: Kotlin
* **UI Framework**: Jetpack Compose (Material 3)
* **Architecture**: MVVM (ViewModel, StateFlow, Coroutines)
* **Bluetooth API**: `android.bluetooth.*`, `android.bluetooth.le.*` (Supports Android 12+ permissions)

## 📄 许可证 (License)

This project is licensed under the MIT License - see the LICENSE file for details.
