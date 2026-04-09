# Team Tracker Official
A bachelor's thesis project focused on improving coordination of teams during airsoft games

## Overview
Mobile application for tracking player positions and statuses in real time using a client-server architecture.

## Features
- Real-time GPS tracking
- Map visualization (Google Maps)
- Lobby system (create/join)
- Player status (alive/dead)
- Automatic removal of inactive players

## Tech Stack
- Android (Java)
- Flask (Python)
- SQLite
- Google Maps API
- Cloudflare Tunnel

## API
- `POST /api/location/update`
- `GET /api/locations`

## Notes
- The system was tested under real conditions with multiple users and provides stable performance with acceptable response times.
- All API Keys and secrets used during development have been replaced with placeholders for security reasons

## Author
https://github.com/Programatoris