good-echo
=========

Time travelling recorder for Android. Free/libre software under the GPL v3.

A fork of [Echo](https://github.com/mafik/echo) by Marek Rogalski (mafik), with Opus encoding, compressed in-memory history, and UI refinements.

Download
---

* [F-Droid](https://f-droid.org/repository/browse/?fdid=com.goodecho.app)

License
-------

Copyright 2014 Marek Rogalski
Copyright 2026 GoodEcho contributors

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

Architecture
---

**SaidItFragment** the main view of the app.

**SaidItService** manages a high priority thread that records audio. The thread is a state machine that can be accessed by sending it tasks using Android's Handler (`audioHandler`).

**OpusRingBuffer** compressed audio frame ring buffer in memory.
