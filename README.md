# Z-Interpreterxxx

Z-Interpreter is an open-source program for playing [Infocom](https://en.wikipedia.org/wiki/Infocom) text adventures.
It is written in Java.

Z-Interpreter implements Infocom's [Z-machine](https://en.wikipedia.org/wiki/Z-machine), a virtual computer developed for playing text adventures. This Z-Interpreter implementation can play text adventures (compiled into "story files") for version 3 of the Z-machine. This applies to many of Infocom's classic text adventures, such as:

| [Ballyhoo](https://en.wikipedia.org/wiki/Ballyhoo_(video_game)) | [Lurking Horror](https://en.wikipedia.org/wiki/The_Lurking_Horror) | [Stationfall](https://en.wikipedia.org/wiki/Stationfall) |
| [Cutthroats](https://en.wikipedia.org/wiki/Cutthroats_(video_game)) | [Moonmist](https://en.wikipedia.org/wiki/Moonmist) | [Suspect](https://en.wikipedia.org/wiki/Suspect_(video_game)) |
| [Deadline](https://en.wikipedia.org/wiki/Deadline_(video_game)) | [Planetfall](https://en.wikipedia.org/wiki/Planetfall) | [Planetfall](https://en.wikipedia.org/wiki/Planetfall) |
| [Enchanter](https://en.wikipedia.org/wiki/Enchanter_(video_game)) | [Plundered Hearts](https://en.wikipedia.org/wiki/Plundered_Hearts) | [Wishbringer](https://en.wikipedia.org/wiki/Wishbringer) |
| [Hitchhiker's Guide To The Galaxy](https://en.wikipedia.org/wiki/The_Hitchhiker%27s_Guide_to_the_Galaxy_(computer_game)) | [Seastalker](https://en.wikipedia.org/wiki/Seastalker) (limited support only)| [Witness](https://en.wikipedia.org/wiki/The_Witness_(1983_video_game)) |
| [Hollywood Hijinx](https://en.wikipedia.org/wiki/Hollywood_Hijinx) | [Sorcerer](https://en.wikipedia.org/wiki/Sorcerer_(video_game)) | [Zork I](https://en.wikipedia.org/wiki/Zork_I) |
| [Infidel](https://en.wikipedia.org/wiki/Infidel_(video_game)) | [Spellbreaker](https://en.wikipedia.org/wiki/Spellbreaker) | [Zork II](https://en.wikipedia.org/wiki/Zork_II) |
| [Leather Goddesses of Phobos](https://en.wikipedia.org/wiki/Leather_Goddesses_of_Phobos) | [Starcross](https://en.wikipedia.org/wiki/Starcross_(video_game)) | [Zork III](https://en.wikipedia.org/wiki/Zork_III) |

Enjoy! &mdash; Lorenz

## Table of Contents
* [Getting Started](#getting-started)
* [Where Do I Get Infocom Story Files?](#where-do-i-get-infocom-story-files)
* [Can I Play Adventure With This, Too?](#can-i-play-adventure-with-this-too)
* [Build Instructions](#build-instructions)
* [Known Limitations](#known-limitations)
* [Licence](#license)

## Getting Started

### Prerequisites
* You have installed Java JDK (or SDK) 8 or higher on your system.
* You have a story file (from Infocom or created with any story file compiler) for version 3 of the Z-machine. 

### Instructions
1. Download [ZInterpreter.jar](https://github.com/lwiest/ZInterpreter/releases/download/latest/ZInterpreter.jar) to a folder.
2. Open a command prompt in that folder and enter
   ``` 
   java -jar ZInterpreter.jar
   ```
   This runs Z-Interpreter and lists command-line options:
   ```
    ____      ___     _                        _           
   |_  / ___ |_ _|_ _| |_ ___ _ _ _ __ _ _ ___| |_ ___ _ _ 
    / / |___| | || ' \  _/ -_) '_| '_ \ '_/ -_)  _/ -_) '_|
   /___|     |___|_||_\__\___|_| | .__/_| \___|\__\___|_|  
                                 |_|                       
   Version 1.3 (13-MAR-2021) (C) by Lorenz Wiest

   Usage: java ZInterpreter [<options>] <story-file>
   Options: -showScoreUpdates | Prints information about the score whenever the score changes.
   ```
   Option `-showScoreUpdates` prints information about the score whenever it changes while playing a story file.

3. To play a story file, for example `ZORK1.DAT`, enter
   ```
   java -jar ZInterpreter.jar ZORK1.DAT
   ```
   Z-Interpreter answers with
   ```
    ____      ___     _                        _           
   |_  / ___ |_ _|_ _| |_ ___ _ _ _ __ _ _ ___| |_ ___ _ _ 
    / / |___| | || ' \  _/ -_) '_| '_ \ '_/ -_)  _/ -_) '_|
   /___|     |___|_||_\__\___|_| | .__/_| \___|\__\___|_|  
                                 |_|                       
   Version 1.3 (13-MAR-2021) (C) by Lorenz Wiest

   ZORK I: The Great Underground Empire
   Copyright (c) 1981, 1982, 1983 Infocom, Inc. All rights reserved.
   ZORK is a registered trademark of Infocom, Inc.
   Revision 88 / Serial number 840726

   West of House
   You are standing in an open field west of a white house, with a boarded front
   door.
   There is a small mailbox here.

   >_
   ```

## Where Do I Get Infocom Story Files?
You can find free story files of _Zork I_, _Zork II_, and _Zork III_ [here](http://www.infocom-if.org/downloads/downloads.html). The story files `ZORK1.DAT`, `ZORK2.DAT`, 
and `ZORK3.DAT` are contained in the downloadable archives. Story files of other Infocom games are available elsewhere, but not in this GitHub repository. An Internet search may help.

## Can I Play _Adventure_ With This, Too?
Yes. I created a [story file](adventure/Adventure.dat) for [Adventure](https://en.wikipedia.org/wiki/Colossal_Cave_Adventure) from a [version by Jesse McGrew et al.](https://www.ifarchive.org/if-archive/infocom/compilers/zilf/zilf-0.8.zip) plus a [map](adventure/Adventure.Map.pdf) and a [map without hints](adventure/Adventure.MapWithoutHints.pdf). They are all part of this GitHub repository.

## Build Instructions

### Prerequisites
* You have installed Java SDK 8 or higher on your system.

### Instructions
1. Download this project's ZIP file from GitHub and unzip it to a temporary folder.
2. **To work with the Z-Interpreter source code in your Eclipse IDE**, import the `ZInterpreter` project to your Eclipse 
IDE from the temporary folder as an import source _General > Existing Projects into Workspace_.
3. **To compile Z-Interpreter into a convenient JAR file** (Windows only), open a command prompt in the temporary folder 
and enter
   ```
   makejar
   ```
   This produces the `ZInterpreter.jar` file, containing the compiled Z-Interpreter.
   
   (Note that the environment variable `JAVA_HOME` must point to the installation folder of your Java SDK.)

## Known Limitations
Z-Interpreter implements a Z-machine of version 3 as described in [The Z-Machine Standards Document Version 1.0](https://www.ifarchive.org/if-archive/infocom/interpreters/specification/z-spec10-pdf.zip) with the following limitations:
* No support of a status line (as this implementation uses a teletype output metaphor)
* No support of sounds
* No support of redirecting input and output
* No support of writing text at arbitrary screen locations (as this implementation uses a teletype output metaphor). Incidentally, this is the reason for the limited support of _Seastalker_, which uses a split screen.

## License
This project is available under the MIT license.
