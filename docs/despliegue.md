# Despliegue y configuración

## Desarrollo local

Arranca la imagen de evaluación y los dos servicios como se indica en el README. El puerto del juego se publica en la interfaz local. El evaluador solo se conecta a la red interna de Compose y conserva su propio acceso al socket de Docker. El servidor recibe los veredictos por HTTP privado con un token compartido.

Si usas Docker rootless, localiza su socket con este comando y pon la ruta de archivo, sin el prefijo **unix://**, en **CODEDUEL_DOCKER_SOCKET** dentro de **.env**.

    docker context inspect --format '{{.Endpoints.docker.Host}}'

Puedes arrancar los servicios directamente en el host. Necesitas un JRE 21, un Docker Engine accesible desde el proceso del evaluador y la imagen **codeduel-sandbox:1.0.0**. Exporta el mismo **CODEDUEL_JUDGE_TOKEN** en las dos terminales.

    codeduel-judge/build/install/codeduel-judge/bin/codeduel-judge

    java -jar codeduel-server/build/libs/codeduel-server.jar

El estado de **GET /health** indica que el proceso del juego está atendiendo HTTP. Para comprobar también el evaluador, inicia una partida y ejecuta una solución correcta con **Ejecutar pruebas**.

## Dos equipos conectados por Internet

1. Prepara un host Linux dedicado en **x86_64** o **arm64**, con Docker rootless, cgroups v2 y controladores de CPU, memoria y procesos delegados. Para aceptar código de desconocidos, configura y valida un runtime de aislamiento como **gVisor**. Puedes seleccionarlo con **CODEDUEL_SANDBOX_RUNTIME=runsc**. El evaluador controla el daemon a través del socket.
2. Copia la distribución al host. Genera el token y arranca Compose. Confirma que responde **http://127.0.0.1:8080/health**.
3. Apunta el DNS de un dominio al host. Abre al exterior TCP 80 y 443 y configura Caddy con el siguiente bloque. Caddy obtiene un certificado y reenvía la conexión WebSocket al servidor local.

       duelos.example.com {
           reverse_proxy 127.0.0.1:8080
       }

4. Desde otra red, comprueba **https://duelos.example.com/health**. Configura **wss://duelos.example.com/duel** en las dos instalaciones de IntelliJ. Crea una sala, únete, ejecuta las pruebas y confirma que ambos ven el resultado.

Dale a cada despliegue un token aleatorio propio. Mantén el socket Docker y el puerto del evaluador en el host privado. Monta el evaluador y el runtime de usuario en un host dedicado, sin credenciales de otros servicios. Usa una única réplica del servidor por despliegue porque las salas viven en memoria. Si cambias de puerto publicado, cambia también el destino del proxy.

## Variables

| Variable | Valor predeterminado y uso |
| --- | --- |
| CODEDUEL_JUDGE_TOKEN | Obligatorio. Mismo valor de 32 a 256 caracteres ASCII sin espacios en el servidor y en el evaluador |
| CODEDUEL_BIND / CODEDUEL_PORT | 127.0.0.1 y 8080 al ejecutar el servidor directamente |
| CODEDUEL_JUDGE_URL | http://127.0.0.1:8091/evaluate al ejecutar el servidor directamente |
| CODEDUEL_JUDGE_BIND / CODEDUEL_JUDGE_PORT | 127.0.0.1 y 8091 al ejecutar el evaluador directamente |
| CODEDUEL_PUBLIC_PORT | 8080 para el puerto publicado por Compose |
| CODEDUEL_DOCKER_SOCKET | /var/run/docker.sock para el socket montado en Compose |
| CODEDUEL_SANDBOX_IMAGE | codeduel-sandbox:1.0.0 cuando el evaluador se ejecuta directamente |
| CODEDUEL_SANDBOX_RUNTIME | Runtime por defecto del daemon si se omite |
| CODEDUEL_JUDGE_PARALLELISM | 4 para la concurrencia del evaluador directo |

Si el trabajo devuelve **Evaluador no disponible**, comprueba **docker compose logs judge**, el acceso al socket, la imagen del sandbox y la compatibilidad del runtime con seccomp. **docker info** permite confirmar que Docker aplica también los límites de cgroups. Si el puerto local está ocupado, usa **CODEDUEL_PUBLIC_PORT** en el archivo **.env**. Para ver el estado del despliegue o detenerlo, ejecuta **docker compose ps** o **docker compose down**.
