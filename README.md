# CodeDuel

CodeDuel is an IntelliJ IDEA plugin for live Java coding duels. Create a private room, share its invite code, and solve one of 15 challenges in the IDE. Run public tests before submitting your solution. The first submission that passes all public and hidden tests wins.

A project by **dumi.jr**.

## Compatibility

CodeDuel 1.0.0 targets IntelliJ IDEA 2025.3.x. The interface is in Spanish. Both players need access to the same CodeDuel server.

## Build

Install JDK 21 and build the plugin with the Gradle Wrapper.

    ./gradlew :codeduel-plugin:buildPlugin

On Windows, use **gradlew.bat**. The plugin ZIP is generated in **codeduel-plugin/build/distributions**.

Install the ZIP using **Settings → Plugins → Install Plugin from Disk**. Open a project and select **View → Tool Windows → CodeDuel**. Use **Configurar servidor…** to enter the server's WebSocket URL. For a remote server, use its **wss://** endpoint.

## Source and license

This repository contains the IntelliJ plugin and its shared protocol and public challenge catalog. The CodeDuel server and evaluator are separate services.

CodeDuel is available under the [Apache License 2.0](LICENSE).
