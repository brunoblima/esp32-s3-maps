CatDrive is a fun project that displays Google Map navigation on a small screen that I attach to my motorbike.

[![Thumbnail](https://img.youtube.com/vi/bleMd7QEXfQ/0.jpg)](https://www.youtube.com/watch?v=bleMd7QEXfQ)


[Read blog](https://maisonsmd.dev/blog/google-maps-on-esp32)

## HELP WANTED!

The current state of this project works fine for me, but if you can make it even better, for example:

- Make the app UI look more professional
- Display incomming call / messages notifications
- Clean up the code (I wrote it in just some days so it's kinda messy)

I really appeciate it!

# How it works

The Android app must be installed and running on your phone, it will read the current navigation data from Google Maps and send it to the ESP32 over Bluetooth Low Energy (BLE).

## Hardware

You will need a ESP32-C6 (or other with BLE capabilities) with a small screen.

Mine is [ESP32-C6 1.47inch Display Development Board](https://www.waveshare.com/esp32-c6-lcd-1.47.htm) from Waveshare. If you use a different screen, you will need to adapt the code to your screen.

## Software

### ESP32

- The code is built with Arduino (v2), you need to install the ESP32 board support in Arduino IDE first.
- Install `lvgl` version **9.2.2**. This project uses the LVGL 9 API and is not compatible with LVGL 8.x.
- Copy `lv_conf.h` to Arduino's libraries folder (not inside `lvgl` folder).
- Make sure the screen works by using the example sketch provided by [Waveshare](https://www.waveshare.com/wiki/ESP32-C6-LCD-1.47)
- Build the code and upload it to your ESP32.
- On the Waveshare ESP32-C6-LCD-1.47, insert a FAT32 microSD card before powering on the board to record BLE and navigation events to `/catdrive.log`. The firmware keeps up to three 1 MiB log files (`catdrive.log`, `.1`, and `.2`). Power off the board before removing the card and reading the files on a computer.

### Android

Currently I make the app specificly for my Pixel 4a phone running Android 13, so most APIs are level 33 or above.

- Install Android Studio and SDK for Android 13.
- Open `android-app` folder in Android Studio.

### Note

The fonts are generated with only a small subset of Unicode characters, I made it specificly for Vietnamese, the characters are:

```plain
!"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\]^_`abcdefghijklmnopqrstuvwxyz{|}~
áàảãạăắằẳẵặâấầẩẫậéèẻẽẹêếềểễệíìỉĩịóòỏõọôốồổỗộơớờởỡợúùủũụưứừửữựýỳỷỹỵđ
ÁÀẢÃẠĂẮẰẲẴẶÂẤẦẨẪẬÉÈẺẼẸÊẾỀỂỄỆÍÌỈĨỊÓÒỎÕỌÔỐỒỔỖỘƠỚỜỞỠỢÚÙỦŨỤƯỨỪỬỮỰÝỲỶỸỴĐ
```

If you want to use different fonts or different set of characters, generate it using this tool https://lvgl.io/tools/fontconverter

Options:
- Size: check each font file name, different sizes and weights used for different purposes
- Bbp: 4
- Enable Font compression: No