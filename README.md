# BCU Crazy Patch

Control your cats yourself, and play two game modes BCU does not have.

Your BCU is not changed. This runs alongside the game and disappears the moment you
start BCU normally again. Delete the folder and everything is back to how it was.

## What you get

**Crazy Patch** lets you take the wheel in battle. Steer a cat with the keyboard, aim
the sniper yourself, and use things the normal game does not have: cats that grow, beam
weapons, bases you can build and edit, a summoning ritual, cat stacking, rebirth on
death, and cats that bump into each other instead of walking through each other.

**Adventure Mode** is a different game. You control one cat in real time - walk, jump,
attack - through a side-scrolling stage. No cannon, no worker cat, no retreat.

**Custom Map Studio** lets you draw your own terrain for those modes: ground, water,
ice, moving platforms, backgrounds and decorations.

You choose which ones to turn on each time you start.

## Install

1. Go to [Releases](https://github.com/privatemerchanttbc-bcu/bcu-crazy-patch/releases)
   and download the zip file.
2. Unzip it. You get a folder called **BCU Crazy**.
3. Put that folder inside your BCU folder - the folder that has `BCU-0-5-8-8.jar` in it.
4. Open **Play BCU with Crazy Patch.bat** instead of starting BCU the usual way.

You need Java installed. If BCU already runs on your computer, you have it.

A window appears the first time asking which features to turn on. Tick what you want and
press Start BCU.

## If something goes wrong

**"No BCU jar found"** - the BCU Crazy folder is in the wrong place. It has to sit inside
your BCU folder, right next to the BCU file itself.

**Nothing looks different** - make sure you opened `Play BCU with Crazy Patch.bat` and not
BCU itself, and that the feature you wanted is ticked.

**Custom Map Studio looks empty** - it needs the `Tiles` folder in your BCU folder to have
anything to draw with.

**Anything else** - there is a file called `manual-control.log` in the BCU Crazy folder.
Send it along with your report.

## Build it yourself

Only needed if you want to change the code. You need JDK 17 or newer.

    git clone https://github.com/privatemerchanttbc-bcu/bcu-crazy-patch
    cd bcu-crazy-patch
    build\build.bat "C:\path\to\BCU-0-5-8-8.jar"

Point it at the BCU file inside your real BCU folder, not a copy on its own - it needs the
`BCU_lib` folder that sits next to it. If `javac` is not on your PATH, set `JAVA_HOME`, or
unpack a JDK into a `jdk` folder inside the repo.

`build\build-installer.bat` then packs the result into `download\BCU Crazy\`. Downloading
this repo on its own does not give you a playable copy - only the release zip does.

## Author

Private Merchant - [YouTube](https://www.youtube.com/channel/UCDyvXQBtpDc3eLassyvxxQw)

Found a bug, or have an idea?
[Open an issue](https://github.com/privatemerchanttbc-bcu/bcu-crazy-patch/issues).
