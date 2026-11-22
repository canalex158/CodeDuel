# CodeDuel

Plugin de IntelliJ IDEA para competir por parejas resolviendo retos Java. Uno crea una sala, comparte el código de ocho caracteres y elige el reto. Tras una cuenta atrás de tres segundos, ambos escriben en el editor de IntelliJ. Gana el primer envío, por orden de recepción del servidor, que supera todos los casos públicos y ocultos.

Un proyecto de **dumi.jr**.

## Instalar y jugar

1. En IntelliJ IDEA **2025.3.x**, abre **Settings → Plugins → ⚙ → Install Plugin from Disk…**, elige **codeduel-plugin-1.0.0.zip** y reinicia el IDE.
2. Abre un proyecto y muestra **CodeDuel** desde la barra lateral derecha o desde **View → Tool Windows → CodeDuel**.
3. En **Configurar servidor…**, pon la misma URL en los dos equipos, por ejemplo **wss://duelos.example.com/duel**. Para probar en el equipo que ejecuta el servidor, usa **ws://127.0.0.1:8080/duel**.
4. Escribe tu nombre y crea la partida. Copia el código y compártelo con tu amigo. Tu amigo escribe su nombre y el código, y pulsa **Unirse a partida**.
5. El anfitrión selecciona el reto e inicia el duelo. CodeDuel abre un **Solution.java** nuevo en el editor al comenzar. Implementa la firma mostrada y deja el archivo sin una declaración de paquete.
6. **Ejecutar pruebas** comprueba los casos públicos. **Enviar solución** comprueba también los ocultos y cuenta para el resultado. Cuando los dos pulsáis **Pedir revancha**, volvéis a la sala.

Cada duelo usa un archivo temporal de IntelliJ (*scratch*) distinto. Puedes volver a abrirlo con **Abrir Solution.java** durante el duelo. IntelliJ conserva esos archivos en **Scratches and Consoles**. Si la red falla, el plugin intenta recuperar tu sitio y el servidor lo reserva durante 60 segundos desde que detecta la desconexión.

## Arrancar el servidor

Para usar la distribución, descomprime **CodeDuel-1.0.0.zip**, entra en su carpeta y ejecuta los comandos de Docker. La distribución incluye el JAR y el evaluador en las rutas de compilación que usan los Dockerfiles.

Desde un checkout del repositorio, copia primero el archivo privado **codeduel-judge/src/main/resources/codeduel/suites.json** desde la distribución completa a esa misma ruta en el checkout. El archivo contiene los casos del evaluador y se conserva como recurso privado de cada despliegue. Después genera los dos binarios con un JDK 21.

    ./gradlew :codeduel-server:bootJar :codeduel-judge:installDist

Con Docker Engine en Linux, Docker Compose y OpenSSL instalados, crea un archivo **.env** nuevo y arranca los servicios.

    (umask 077; set -C; printf 'CODEDUEL_JUDGE_TOKEN=%s\n' "$(openssl rand -hex 32)" > .env)
    docker compose --profile build build sandbox
    docker compose up -d --build
    curl --fail http://127.0.0.1:8080/health

Para equipos en Internet, añade el dominio y el proxy HTTPS siguiendo la guía de despliegue. [Despliegue y configuración](docs/despliegue.md)

## Compilar y verificar

El proyecto usa Java 21, Gradle Wrapper 9.4.1, IntelliJ Platform Gradle Plugin 2.20.1 y Spring Boot 3.5.16. El SDK de referencia es IntelliJ IDEA 2025.3.6.2, build 253.33813.99. El manifiesto limita la instalación a la familia **253**.

    ./gradlew test :codeduel-plugin:buildPlugin :codeduel-server:bootJar :codeduel-judge:distZip
    ./gradlew :codeduel-plugin:verifyPluginProjectConfiguration :codeduel-plugin:verifyPluginStructure :codeduel-plugin:verifyPlugin

Puedes usar **CODEDUEL_IDEA_HOME** con la ruta de una instalación local **2025.3.6.2** al compilar o verificar. El archivo instalable aparece en **codeduel-plugin/build/distributions**. El servidor ejecutable aparece en **codeduel-server/build/libs**. El ZIP del evaluador aparece en **codeduel-judge/build/distributions**. En Windows, usa **gradlew.bat**.

- [Arquitectura y protocolo](docs/arquitectura.md)
- [Despliegue y configuración](docs/despliegue.md)
- [Publicación en JetBrains Marketplace](docs/marketplace.md)
- [Pruebas y resultados](docs/pruebas.md)

CodeDuel se distribuye con [licencia Apache 2.0](LICENSE).
