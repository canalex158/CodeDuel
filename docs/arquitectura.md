# Arquitectura de CodeDuel

| Módulo | Responsabilidad |
| --- | --- |
| codeduel-plugin | Ventana Swing de IntelliJ, editor Java, conexión WebSocket y recuperación de sesión |
| codeduel-server | Salas, participantes, cronómetro, turnos, resultados y cola de evaluaciones |
| codeduel-judge | Servicio privado, compilación en Docker y comparación de resultados |
| codeduel-shared | Contrato JSON compartido y catálogo público |

## Salas y resultados

El servidor genera códigos aleatorios de ocho caracteres y un token de 256 bits por asiento. El código permite ocupar un asiento libre. El token permite recuperar un asiento existente y se comunica solo a su jugador. El plugin guarda el token en memoria durante la sesión. El servidor conserva las salas en memoria y el reinicio cierra las partidas.

Solo el anfitrión cambia el reto e inicia el duelo con ambos jugadores conectados. El servidor cambia de fase de sala a cuenta atrás, duelo activo y resultado. Usa su reloj para fijar la hora de inicio, el cierre de los 30 minutos de juego y el tiempo de la solución ganadora. El plugin calibra su cronómetro con mensajes PING y PONG.

Cada jugador puede tener una evaluación en curso. Los envíos oficiales reciben una posición en la cola de su ronda al llegar al servidor. Si un envío posterior se evalúa antes, el servidor espera el anterior. Cuando encuentra el primer envío correcto en ese orden, difunde el mismo resultado a los dos clientes. Los envíos en el mismo milisegundo se ordenan según el orden de admisión. Ejecutar casos públicos nunca produce una victoria.

El servidor admite los envíos recibidos a tiempo aunque el juez todavía los esté procesando al vencer el plazo. Si nadie supera los casos, el duelo termina sin ganador al expirar el tiempo o cuando un participante abandona y se resuelven los envíos pendientes. Durante una desconexión temporal el asiento permanece reservado 60 segundos desde la detección del servidor.

## WebSocket

**GET /duel** usa el protocolo JSON versión 1. Cada mensaje del cliente tiene **action**. Cada respuesta lleva **event** y **serverTime** en milisegundos Unix. Los campos opcionales se omiten.

| Cliente | Campos adicionales |
| --- | --- |
| CREATE | name |
| JOIN | name, code |
| RESUME | code, token |
| SELECT | challengeId |
| START | |
| RUN, SUBMIT | roundId, source |
| REMATCH, LEAVE | |
| PING | pingAt, valor opaco del cliente |

El servidor envía **HELLO** con **hello.version** y **hello.challenges**. **SESSION** incluye **session.code**, **session.playerId** y **session.token**. **ROOM** contiene **room.phase**, los participantes, el identificador de ronda, los tiempos y el resultado. **EVALUATION** envía al autor el veredicto de su código. **ERROR** incluye un código y un mensaje, **LEFT** confirma salida y **PONG** devuelve **pingAt**.

## Evaluación

Cada caso usa un contenedor nuevo. El servicio genera un lanzador con la firma y la entrada del caso y transmite el lanzador con la fuente. El contenedor ejecuta **javac --release 21 -proc:none** y llama al método de la solución. El servicio compara la salida capturada con la respuesta esperada. Mantiene las respuestas esperadas y los demás casos fuera del contenedor.

Los contenedores se ejecutan con UID 65534, sin red, sin capacidades, con **no-new-privileges** y sistema de archivos de solo lectura. Solo el directorio temporal de 32 MiB permite escritura. Docker recibe límites de 1 CPU, 256 MiB de RAM sin swap y 48 tareas por contenedor.

Un lanzador nativo fija la afinidad a un procesador y limita el espacio de direcciones de cada JVM a 640 MiB y su tiempo de CPU a 5 segundos. Un filtro seccomp impide que el código del caso cree procesos adicionales, cambie su afinidad o cambie de espacio de nombres. El caso tiene 7 segundos de tiempo de pared dentro del contenedor. Estos límites por proceso siguen operativos en hosts rootless sin controladores de cgroups delegados. Si el lanzador no consigue instalar sus límites, el evaluador devuelve un error de disponibilidad. La salida capturada también está acotada. Se evalúan hasta cuatro trabajos en paralelo.

Los casos ocultos pertenecen al módulo **codeduel-judge** y al código fuente para administradores. Para que sigan siendo secretos frente a los jugadores, distribuye a los jugadores únicamente el ZIP del plugin. Un despliegue público puede sustituir o ampliar los casos en **suites.json** y reconstruir el evaluador.
