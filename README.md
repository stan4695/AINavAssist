# AINavAssist: Automatic detection system for people with visual impairments

This is my Bachelor's thesis project, developed at the Faculty of Electronics, Telecommunications and Information Technology in Bucharest (ETTI). The goal is to help visually impaired individuals move around more safely by using real-time object detection and voice feedback on an Android device.

## 📱 Description

The mobile app uses a custom-trained YOLOv10n model to detect obstacles like benches, trash bins, tree trunks, bushes, fences, or pillars. After detection, the app provides voice feedback using Android's Text-to-Speech system to notify the user about what lies ahead.
<P align="center">
  <img width="509" height="962" alt="image" src="https://github.com/user-attachments/assets/35400c36-cbcb-485a-8ee0-e1512bca1ce9" />
</P>

## 🚀 Main Features
1. Real-Time Obstacle Detection
  - The app uses the YOLOv10n model to detect, classify, and localize obstacles.
  - It indicates the direction of each obstacle relative to the user: left, right, or ahead.

2. Distance Estimation
- The app receives distance data via Bluetooth from external sensors connected to an ESP32 microcontroller.

3. Multimodal Alerts
- Vocal: Real-time audio messages via Text-to-Speech (TTS).
- Tactile: Variable vibrations based on obstacle distance — both intensity and duration change depending on how close the object is.

4. Customizable Settings
- Adjust the detection sensitivity by configuring the confidence threshold.
- Enable or disable TTS and vibration as needed.
- Optional GPU acceleration support for faster inference on compatible devices.

## Arhitecture & Technologies

<img width="1453" height="702" alt="image" src="https://github.com/user-attachments/assets/549a44ab-d6ee-4cbe-97f1-447e812c0413" />

### 🛠️ Technologies Used

- Kotlin
- YOLOv10n (Ultralytics)
- PyTorch + TFLite
- OpenCV
- CVAT
- Android Text-to-Speech API
- Ubuntu 22.04 (for model training)

## 📄 Documentation & Presentation

[Presentation Slides](https://github.com/user-attachments/files/21565430/AINavAssist.pptx)

