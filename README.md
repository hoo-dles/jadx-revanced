# revanced-script

Run ./gradlew app:dist to build the plugin then in app/build/dist you find the jar

You need the github token for accessing the revanced-patcher repo


The utils module has nothing its just testing

To uninstall i believe jadx is kinda bugged, you have to uninstall the plugin from UI and then find the folder where jadx installs plugins and delete the jar there in windows is %appdata%\Roaming\skylot\jadx\config\plugins\installed


TODO: right click button to copy fingerprint

TODO: Fix jumping to nested classes not working, this is a jadx issue tho, might need to find a better way to translate short 