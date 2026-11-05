# Terraria Auto Fisher for Mobile

An open-source Android app that automatically reels and recasts in Terraria. It watches your fishing bobber or line for a bite, then taps the fishing button for you.

## Download

Download the APK from [Releases](https://github.com/ArcTea7/TerrariaAutoFisher/releases/latest) and install it on your phone. Alternatively, you can build the app yourself from the source code.

Having trouble installing? See [Installation help](INSTALL.md).

Requires Android 11 or newer.

## Setup

1. Open the app and enable its Accessibility service when prompted.
2. Open Terraria, equip your fishing rod and cast.
3. Open the floating menu and choose **Bobber** or **Line** mode.
4. Tap **Capture** and approve screen sharing. If Android offers a choice, select the entire screen.
5. Select your bobber or line as described below, then save the capture.
6. Tap **Edit** in the floating menu. Drag the fishing-button marker onto Terraria’s fishing button, then tap **Lock**.
7. Tap **Start** and keep Terraria visible.

### Bobber mode

Place the capture box around the resting bobber. In the image editor, move the image to center the bobber in the square. Use **Erase** to exclude surrounding water, then save.

Keep the selection close to the bobber. Including too much moving water can cause it to track the background instead.

### Line mode

Capture the resting fishing line. In the image editor, use **Pan** to move or zoom the image, then choose **Select line** and tap the line. Check the traced outline before saving.

Choose a clear section of the line. A longer section away from the water or lava surface can help avoid interference from splashes.

## Tips and limitations

Use **Inspect** to see the selected area and what the app is tracking.

Changes in position, zoom or lighting may require a new capture.

Bobber mode can struggle with particles and splashes around the bobber, particularly in lava. These can trigger an early reel. Try Line mode if this happens frequently.

Line mode may not detect the line in every environment. Its colour needs to stand out from the background. If it cannot find the line, try selecting another section, changing the background settings, or moving to a spot with better contrast. Bobber mode is another option.

**Optional:** Try turning off **Views → Backgrounds** or **Views → Screen effects** in Terraria’s settings. This may reduce interference. Backgrounds can also make the fishing line easier to see in some locations, so keep them enabled if that gives better results.

## How it works

Bobber mode compares the selected bobber with a saved picture and looks for changes during a bite.

Line mode follows the selected fishing line and looks for a larger movement along its length.

When a bite is detected, the app taps your fishing button to reel, then taps it again to recast.

## Problems

[Open an issue](https://github.com/ArcTea7/TerrariaAutoFisher/issues) if something isn’t working. Include your phone model, detection mode and what happened.
