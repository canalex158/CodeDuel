# Publicación en JetBrains Marketplace

1. Compila **:codeduel-plugin:buildPlugin** con un JDK 21. Verifica **:codeduel-plugin:verifyPluginProjectConfiguration**, **:codeduel-plugin:verifyPluginStructure** y **:codeduel-plugin:verifyPlugin**. Repite la verificación con una instalación completa de IntelliJ IDEA del build que planeas publicar.
2. Instala el ZIP en una instalación limpia de IntelliJ IDEA 2025.3.x. Haz una partida con dos instalaciones apuntando al servidor de pruebas con TLS. Comprueba la sala, el editor y el resultado en ambos equipos.
3. Crea el perfil de proveedor de JetBrains Marketplace y sube el ZIP desde **Upload plugin**. Completa la ficha, la licencia, el enlace a soporte y los datos públicos de contacto. El ID estable del plugin es **dev.codeduel.plugin**. Conserva ese ID al preparar versiones posteriores.
4. Para firmar el ZIP con tu propio certificado, configura **JETBRAINS_CERTIFICATE_CHAIN**, **JETBRAINS_PRIVATE_KEY** y, si aplica, **JETBRAINS_PRIVATE_KEY_PASSWORD**. Ejecuta **./gradlew :codeduel-plugin:signPlugin** y valida el resultado con **:codeduel-plugin:verifyPluginSignature**.
5. Tras la primera publicación y con autorización, puedes automatizar las siguientes con **JETBRAINS_MARKETPLACE_TOKEN** y **./gradlew :codeduel-plugin:publishPlugin**. JetBrains revisa los envíos antes de distribuirlos.

## Referencias oficiales

- [Builds compatibles y versiones de Java](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html)
- [IntelliJ Platform Gradle Plugin](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html)
- [Verificación de compatibilidad](https://plugins.jetbrains.com/docs/intellij/verifying-plugin-compatibility.html)
- [Firma de plugins](https://plugins.jetbrains.com/docs/intellij/plugin-signing.html)
- [Publicación de plugins](https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html)
