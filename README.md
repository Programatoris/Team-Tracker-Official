# Team Tracker Official
A bachelor's thesis project focused on improving coordination of teams during airsoft games.

The primary goal of the application is to improve team coordination in dynamic outdoor environments by providing up-to-date positional data and visual feedback directly on a map interface.

## Overview
This project presents the design and implementation of a mobile application intended for real-time tracking of player positions and statuses during airsoft matches. The system was developed as part of a bachelor’s thesis and is based on a client-server architecture, where mobile devices act as clients and a lightweight server handles data processing and synchronization.

<img width="2328" height="1201" alt="realistic_phone_with_screen_transparent" src="https://github.com/user-attachments/assets/d2746195-1853-49dd-a3a2-84a21e021ddc" />

The server stores player data in a SQLite database. Each session (lobby) is logically separated and contains records of all connected players, including their identifiers, names, positions, timestamps, and status information.

## Data Flow

<img width="1672" height="941" alt="709f56cd-649d-4cd7-9085-73a4cac1655f" src="https://github.com/user-attachments/assets/61695d68-dde1-497a-be20-8fff25bf6f57" />

The system operates in a cyclic manner based on periodic communication between client and server. After joining or creating a session, the client repeatedly performs two main operations.

First, it sends its current location and state to the server using a POST request. The server processes the request, updates the corresponding database record, and stores the latest information about the player.

Second, the client requests the positions of other players using a GET request. The server responds with a list of active players in the same session, excluding the requesting client. The client then updates the map by creating, updating, or removing markers based on the received data.

This continuous cycle ensures that all participants have access to up-to-date information about team members.

## Functionality
The application allows users to create or join sessions (lobbies) identified by a unique name. Each player is assigned a persistent identifier to prevent duplication across sessions or application restarts.

The system provides real-time visualization of player positions using map markers. Players can also update their status (alive or eliminated), which is synchronized across all connected clients. Inactive players are automatically removed based on a timeout mechanism to maintain data consistency.

Foreground services were used to maintain location tracking when the device screen is turned off. Network communication was implemented asynchronously to prevent blocking the user interface. The system was also optimized to minimize unnecessary updates and reduce battery consumption.

## Testing
The system was tested under realistic conditions with multiple users connected simultaneously. Testing focused on verifying correct synchronization of player data, stability of communication, and responsiveness under varying network conditions.

The results confirmed that the application maintains consistent performance with acceptable response times and sufficient accuracy for practical use during gameplay.

## Limitations
Despite achieving the main objectives, the system has several limitations. The communication model is based on periodic polling, which introduces a small delay between real-world changes and their visualization in the application.

The system also depends on the availability and quality of internet connectivity, which may vary significantly in outdoor environments. Additionally, GPS accuracy can be affected by terrain, obstacles, or signal interference.

The server implementation, while sufficient for smaller groups, may face scalability limitations under higher load. Future improvements could include migration to a more scalable infrastructure and adoption of real-time communication technologies such as WebSockets or MQTT.

## Security Note
All API keys and sensitive configuration data used during development have been removed or replaced with placeholders. The repository does not contain any sensitiveinformation.

## Author
https://github.com/Programatoris
