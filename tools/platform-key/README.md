# AOSP platform test key

`androiddebugkey.jks` — публичный тестовый ключ платформы Android
(`build/make/target/product/security/platform.pk8` в исходниках AOSP,
SHA1 `27:19:6E:38:6B:87:5E:76:AD:F7:00:E7:EA:84:E4:C6:EE:E3:3D:FA`).
Alias `platformkey`, пароль `android`. Секретом не является — это тот же файл,
что лежит в открытом репозитории zee-power-toys.

Zeekr OS (userdebug) подписан им же, поэтому APK с этой подписью получает
signature-разрешения, включая `android.car.permission.*`. Пока файл здесь,
`app/build.gradle.kts` собирает дополнительный вариант `platform` с пакетом
`com.zeekrprobe.sys`, а CI публикует его как `ZeekrProbe-N-platform.apk`.
