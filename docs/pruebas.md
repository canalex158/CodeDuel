# Registro de verificación de CodeDuel 1.0.0

Comprobaciones ejecutadas el **22 de noviembre de 2026** con JDK 21, Gradle 9.4.1 y Docker 29.9.2. El Gradle Wrapper descargó y verificó la distribución indicada en el proyecto.

| Comprobación | Resultado |
| --- | --- |
| Compilación de los cuatro módulos y generación de los binarios | ZIP del plugin, JAR del servidor y distribución del evaluador generados |
| Configuración y estructura de la extensión | verifyPluginProjectConfiguration y verifyPluginStructure ejecutadas correctamente |
| JetBrains Plugin Verifier para IntelliJ IDEA 2025.3.6.2, build IU-253.33813.99 | Compatible |
| Editor en el entorno de pruebas de IntelliJ | Apertura de Solution.java, edición y conservación del borrador al reabrir el archivo |
| Transporte del plugin contra un servidor WebSocket real | Validación de URL y reconexión automática al mismo asiento y a la misma ronda |
| Arranque desde el ZIP de entrega | Imágenes construidas desde el paquete descomprimido. Servidor y evaluador arrancados con Docker Compose. Respuesta positiva en GET /health |
| Partida con dos clientes WebSocket independientes en Compose | Sala compartida, código inválido, sala llena, control del anfitrión, cuenta atrás común, reconexión, error de compilación, rechazo en caso oculto, victoria válida, resultado idéntico y revancha |
| Catálogo y evaluador | Los 15 retos superaron 121 casos con soluciones de referencia, 45 públicos y 76 ocultos. También pasaron las pruebas de firma incorrecta, error de ejecución, agotamiento de tiempo y bloqueo de procesos secundarios |
| Límites dentro del contenedor | Afinidad a 1 procesador, CPU limitada a 5 segundos, espacio de direcciones limitado a 640 MiB y restricción de procesos verificados en el entorno rootless |
| Envíos concurrentes | Ambos envíos superaron los casos. El segundo terminó de evaluarse primero y el servidor dio la victoria al primer envío en los dos clientes |

Pasaron **26 pruebas automatizadas**. El registro suma 4 del plugin, 2 del catálogo, 18 del evaluador y 2 del protocolo de juego. La batería del evaluador se repitió completa después de ajustar el arranque de la JVM por un fallo intermitente de compilación.

## Repetir las comprobaciones

Desde el proyecto, con un JDK 21 y el archivo **.env** preparado según el README, puedes repetir la batería con estos comandos.

    docker compose --profile build build sandbox
    docker compose up -d --build
    CODEDUEL_TEST_ENDPOINT=ws://127.0.0.1:8080/duel CODEDUEL_DOCKER_TESTS=1 ./gradlew test

Las pruebas sin variables de entorno cubren el catálogo y las pruebas locales del plugin. **CODEDUEL_TEST_ENDPOINT** activa las pruebas con un servidor real. **CODEDUEL_DOCKER_TESTS=1** activa las pruebas con contenedores para los casos del evaluador. En el entorno de verificación, las pruebas del backend se compilaron con Gradle y se ejecutaron con JUnit Platform Console para disponer de espacio durante la ejecución de contenedores.

    ./gradlew :codeduel-plugin:verifyPluginProjectConfiguration :codeduel-plugin:verifyPluginStructure :codeduel-plugin:verifyPlugin

## Validación manual pendiente

La interfaz gráfica se abrió hasta el acuerdo de usuario de IntelliJ. Queda por ejecutar la partida gráfica completa entre dos instalaciones en redes distintas, con el acuerdo aceptado y una instancia desplegada con una URL **wss://**. La guía docs/despliegue.md de la distribución explica cómo preparar esa instancia.
