// Строки меню настроек Howl — общая схема с Windows (Подключение · Маршрутизация · Профиль ·
// Приложение). Тексты взяты из словарей Windows (src/Howl.Core/Localization), где они есть,
// чтобы два приложения говорили одинаково; Android-только строки (запуск при загрузке
// телефона, работа в фоне, приложения в обход) — здесь же.
//
// Запуск: node tools/gen-menu-strings.js — пишет блок между маркерами HOWL-MENU в
// app/src/main/res/values*/strings.xml. fa и zh не заполнены: там, как и для остальных
// строк Howl, Android подставит английский.
const fs = require("fs");
const path = require("path");

const LANGS = [
  ["values", "en"], ["values-ru-rRU", "ru"], ["values-de", "de"],
  ["values-es", "es"], ["values-it", "it"], ["values-fr", "fr"],
];

// en, ru, de, es, it, fr
const S = {
  menu_sec_connection: ["Connection", "Подключение", "Verbindung", "Conexión", "Connessione", "Connexion"],
  menu_sec_routing: ["Routing", "Маршрутизация", "Routing", "Enrutamiento", "Instradamento", "Routage"],
  menu_sec_profile: ["Profile", "Профиль", "Profil", "Perfil", "Profilo", "Profil"],
  menu_sec_app: ["App", "Приложение", "App", "Aplicación", "App", "Application"],

  menu_watchdog: ["Connection self-repair", "Автопочинка соединения", "Automatische Verbindungsreparatur", "Reparación automática", "Riparazione automatica", "Réparation automatique"],
  menu_watchdog_sub: ["If the connection drops, reconnects or switches server by itself", "Пропала связь — сам переподключит или сменит сервер", "Bricht die Verbindung ab, verbindet es neu oder wechselt den Server", "Si se pierde la conexión, se reconecta o cambia de servidor solo", "Se la connessione cade, si riconnette o cambia server da solo", "Si la connexion tombe, se reconnecte ou change de serveur tout seul"],
  menu_kill_switch: ["Block the internet if the VPN drops", "Блокировать интернет при обрыве VPN", "Internet bei VPN-Abbruch blockieren", "Bloquear Internet si la VPN cae", "Blocca Internet se la VPN cade", "Bloquer Internet si le VPN tombe"],
  menu_kill_switch_sub: ["Turned on in Android VPN settings", "Включается в системных настройках VPN", "Wird in den VPN-Einstellungen von Android aktiviert", "Se activa en los ajustes de VPN de Android", "Si attiva nelle impostazioni VPN di Android", "S'active dans les réglages VPN d'Android"],
  menu_autostart: ["Start when the phone boots", "Запускать при загрузке телефона", "Beim Hochfahren des Telefons starten", "Iniciar al encender el teléfono", "Avvia all'accensione del telefono", "Démarrer au démarrage du téléphone"],
  menu_autostart_sub: ["The VPN turns on by itself after a restart", "VPN включится сам после перезагрузки", "Das VPN schaltet sich nach einem Neustart selbst ein", "La VPN se activa sola tras reiniciar", "La VPN si attiva da sola dopo il riavvio", "Le VPN s'active tout seul après un redémarrage"],
  menu_autoconnect: ["Connect when the app opens", "Подключаться при открытии приложения", "Beim Öffnen der App verbinden", "Conectar al abrir la app", "Connetti all'apertura dell'app", "Se connecter à l'ouverture de l'app"],
  menu_autoconnect_sub: ["The VPN turns on by itself, no tap needed", "VPN включится сам, без нажатия кнопки", "Das VPN schaltet sich ohne Tippen selbst ein", "La VPN se activa sola, sin pulsar nada", "La VPN si attiva da sola, senza tocchi", "Le VPN s'active tout seul, sans appui"],
  menu_background: ["Background operation", "Работа в фоне", "Hintergrundbetrieb", "Funcionamiento en segundo plano", "Funzionamento in background", "Fonctionnement en arrière-plan"],
  menu_background_ok: ["Battery optimization is off — good", "Оптимизация батареи выключена — хорошо", "Akku-Optimierung ist aus — gut", "La optimización de batería está desactivada — bien", "L'ottimizzazione della batteria è disattivata — bene", "L'optimisation de la batterie est désactivée — bien"],
  menu_background_bad: ["The system may put the VPN to sleep", "Система может усыпить VPN", "Das System kann das VPN einschläfern", "El sistema puede dormir la VPN", "Il sistema può sospendere la VPN", "Le système peut endormir le VPN"],

  menu_russia: ["Russian sites directly", "Российские сайты напрямую", "Russische Seiten direkt", "Sitios rusos directamente", "Siti russi diretti", "Sites russes en direct"],
  menu_russia_sub: ["Banks, government sites and Russian apps bypass the VPN", "Банки, госуслуги и российские приложения — мимо VPN", "Banken, Behördenseiten und russische Apps am VPN vorbei", "Bancos, sitios públicos y apps rusas fuera de la VPN", "Banche, siti pubblici e app russe fuori dalla VPN", "Banques, sites publics et apps russes hors VPN"],
  menu_split: ["Sites that bypass the VPN", "Сайты в обход VPN", "Websites am VPN vorbei", "Sitios fuera de la VPN", "Siti fuori dalla VPN", "Sites hors VPN"],
  menu_split_none: ["None set", "Не заданы", "Keine festgelegt", "Ninguno", "Nessuno", "Aucun"],
  menu_apps: ["Apps that bypass the VPN", "Приложения в обход VPN", "Apps am VPN vorbei", "Apps fuera de la VPN", "App fuori dalla VPN", "Apps hors VPN"],
  menu_apps_on: ["On · apps: %1$d", "Включено · приложений: %1$d", "An · Apps: %1$d", "Activado · apps: %1$d", "Attivo · app: %1$d", "Activé · apps : %1$d"],
  menu_apps_off: ["Off", "Выключено", "Aus", "Desactivado", "Disattivato", "Désactivé"],
  menu_dns: ["DNS", "DNS", "DNS", "DNS", "DNS", "DNS"],

  menu_subscription: ["Subscription", "Подписка", "Abonnement", "Suscripción", "Abbonamento", "Abonnement"],
  menu_subscription_updated: ["Last updated: %1$s", "Последнее обновление: %1$s", "Zuletzt aktualisiert: %1$s", "Última actualización: %1$s", "Ultimo aggiornamento: %1$s", "Dernière mise à jour : %1$s"],
  menu_subscription_none: ["No subscription yet", "Подписка не добавлена", "Noch kein Abonnement", "Aún no hay suscripción", "Ancora nessun abbonamento", "Pas encore d'abonnement"],
  menu_add_server: ["Add server", "Добавить сервер", "Server hinzufügen", "Añadir servidor", "Aggiungi server", "Ajouter un serveur"],
  menu_add_server_sub: ["A link, QR code, file or config from another service", "Ссылка, QR-код, файл или конфиг другого сервиса", "Link, QR-Code, Datei oder Konfiguration eines anderen Dienstes", "Enlace, código QR, archivo o configuración de otro servicio", "Link, codice QR, file o configurazione di un altro servizio", "Lien, code QR, fichier ou configuration d'un autre service"],

  menu_language: ["Language", "Язык", "Sprache", "Idioma", "Lingua", "Langue"],
  menu_language_system: ["As in the system", "Как в системе", "Wie im System", "Como el sistema", "Come nel sistema", "Comme le système"],
  menu_update: ["Update", "Обновление", "Update", "Actualización", "Aggiornamento", "Mise à jour"],
  menu_update_version: ["Howl for Android %1$s", "Howl для Android %1$s", "Howl für Android %1$s", "Howl para Android %1$s", "Howl per Android %1$s", "Howl pour Android %1$s"],
  menu_update_available: ["Version %1$s is available · %2$d MB", "Доступна версия %1$s · %2$d МБ", "Version %1$s ist verfügbar · %2$d MB", "La versión %1$s está disponible · %2$d MB", "È disponibile la versione %1$s · %2$d MB", "La version %1$s est disponible · %2$d Mo"],
  menu_update_available_nosize: ["Version %1$s is available", "Доступна версия %1$s", "Version %1$s ist verfügbar", "La versión %1$s está disponible", "È disponibile la versione %1$s", "La version %1$s est disponible"],
  menu_update_checking: ["Checking…", "Проверяю…", "Prüfe…", "Comprobando…", "Controllo…", "Vérification…"],
  menu_update_latest: ["You have the latest version", "Установлена последняя версия", "Sie haben die neueste Version", "Tiene la última versión", "Hai l'ultima versione", "Vous avez la dernière version"],
  menu_update_check: ["Check", "Проверить", "Prüfen", "Comprobar", "Verifica", "Vérifier"],
  menu_update_install: ["Install", "Установить", "Installieren", "Instalar", "Installa", "Installer"],
  menu_app_more: ["Notifications and updates", "Уведомления и обновления", "Benachrichtigungen und Updates", "Notificaciones y actualizaciones", "Notifiche e aggiornamenti", "Notifications et mises à jour"],
  menu_app_more_sub: ["Speed in the notification, update channel and auto-update", "Скорость в уведомлении, канал и автообновление", "Tempo in der Benachrichtigung, Update-Kanal und Auto-Update", "Velocidad en la notificación, canal y actualización automática", "Velocità nella notifica, canale e aggiornamento automatico", "Débit dans la notification, canal et mise à jour auto"],
  menu_diagnostics: ["Diagnostics", "Диагностика", "Diagnose", "Diagnóstico", "Diagnostica", "Diagnostic"],
  menu_diagnostics_sub: ["Log and versions", "Журнал и версии", "Protokoll und Versionen", "Registro y versiones", "Registro e versioni", "Journal et versions"],
  menu_diagnostics_versions: ["Versions", "Версии", "Versionen", "Versiones", "Versioni", "Versions"],
  menu_diagnostics_app: ["App: %1$s", "Приложение: %1$s", "App: %1$s", "Aplicación: %1$s", "App: %1$s", "Application : %1$s"],
  menu_diagnostics_core: ["Core: %1$s", "Ядро: %1$s", "Kern: %1$s", "Núcleo: %1$s", "Core: %1$s", "Cœur : %1$s"],
  menu_diagnostics_open_log: ["Show log", "Показать журнал", "Protokoll anzeigen", "Mostrar registro", "Mostra registro", "Afficher le journal"],
};

function esc(s) {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
    .replace(/'/g, "\\'").replace(/"/g, '\\"');
}

const res = path.join(__dirname, "..", "app", "src", "main", "res");
const BEGIN = "    <!-- HOWL-MENU: начало (tools/gen-menu-strings.js, руками не править) -->";
const END = "    <!-- HOWL-MENU: конец -->";
LANGS.forEach(([dir], idx) => {
  const file = path.join(res, dir, "strings.xml");
  let xml = fs.readFileSync(file, "utf8");
  const eol = xml.includes("\r\n") ? "\r\n" : "\n";
  const lines = [BEGIN];
  for (const [key, values] of Object.entries(S)) {
    const v = values[idx];
    if (typeof v !== "string" || !v) throw new Error(`${dir}:${key} не заполнено`);
    lines.push(`    <string name="${key}">${esc(v)}</string>`);
  }
  lines.push(END);
  const block = lines.join(eol);
  const re = new RegExp(`    <!-- HOWL-MENU: начало[\\s\\S]*?<!-- HOWL-MENU: конец -->`);
  if (re.test(xml)) xml = xml.replace(re, block);
  else xml = xml.replace(/<\/resources>\s*$/, block + eol + "</resources>" + eol);
  fs.writeFileSync(file, xml, "utf8");
});
console.log(`ключей: ${Object.keys(S).length}, языков: ${LANGS.length}`);
