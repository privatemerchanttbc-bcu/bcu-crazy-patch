# BCU Crazy Patch

Control your cats yourself, and play two game modes BCU does not have.

Your BCU is not changed. This runs alongside the game and disappears the moment you
start BCU normally again. Delete the folder and everything is back to how it was.

## What you get

Three things, and you pick which ones load each time you start.

### Crazy Patch

Take the wheel in battle. Grab a cat and steer it with the keyboard, aim the
sniper yourself, and turn on any of these:

**Your cats**

| | |
|---|---|
| Growing Units | cats get bigger as the fight goes on |
| Stack Unit | redeploy a cat you already have and it stacks: +10% health, attack, range and size, +1% cooldown |
| Reincarnation | an expensive cat that dies hatches into two random cats worth the same |
| The Ritual | sacrifice cats to summon something else |
| Egg Pet | a pet hatches from an egg and fights with you |
| Cat Coin | flip a coin, live with the result |
| Boss Item | drag the battle icon onto a cat, once per fight |
| Suicide Bomb | drag a bomb onto one of your own cats |
| Dice Slot | roll for it |
| Booster Slot | drag a lineup button into the dock beside your lineup |
| Impact Fall | cats get knocked into the air and come back down |

**Your base**

| | |
|---|---|
| Extra Player Bases | more bases, spawning either the same cat or a higher form |
| Interactive Player Base | a base you can actually use |
| Slingshot Base | fire cats out of a slingshot |
| Cat Canon | a cannon, but yours |
| Edit Player Base HP | set your base health to whatever you like |

**Beam weapons** - pick one:

| | |
|---|---|
| Hypnosis Beam | turn an enemy to your side |
| Kamehameha Beam | charge it up, then let go |
| Evolution Beam | lift a cat and morph it into another form |
| Army Canon | a cannon that fires an army |
| Copy Cat UFO | copies what it hits |

**Physics** - cats collide by their actual sprite outline instead of walking
through each other. F7 turns it on and off, F8 shows the outlines. Small cats
get juggled by heavy hits, and the dead get launched.

**Advanced summoning** - set up cats that summon on hit, on kill or on a miss,
or summon riders that cling to a body part before dropping off. Both come with
their own editor.

**Battle rules** - change unit costs, or start the fight with full money.

### Adventure Mode

A different game. You control one cat in real time - walk, jump, attack -
through a side-scrolling stage. No cannon, no worker cat, no retreat.

### Custom Map Studio

Draw your own terrain for those modes: ground, water, ice, moving platforms,
backgrounds and decorations.

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
