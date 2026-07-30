package io.nekohasekai.sfa.constant

object SettingsKey {
    const val SELECTED_PROFILE = "selected_profile"
    const val SERVICE_MODE = "service_mode"
    const val CHECK_UPDATE_ENABLED = "check_update_enabled"
    const val UPDATE_CHECK_PROMPTED = "update_check_prompted"
    const val UPDATE_SOURCE = "update_source"
    const val UPDATE_TRACK = "update_track"
    const val FDROID_MIRROR_URL = "fdroid_mirror_url"
    const val FDROID_CUSTOM_MIRRORS = "fdroid_custom_mirrors"
    const val SILENT_INSTALL_ENABLED = "silent_install_enabled"
    const val SILENT_INSTALL_METHOD = "silent_install_method"
    const val AUTO_UPDATE_ENABLED = "auto_update_enabled"
    const val DYNAMIC_NOTIFICATION = "dynamic_notification"
    const val DISABLE_DEPRECATED_WARNINGS = "disable_deprecated_warnings"

    const val AUTO_REDIRECT = "auto_redirect"
    const val PER_APP_PROXY_ENABLED = "per_app_proxy_enabled"
    const val PER_APP_PROXY_MODE = "per_app_proxy_mode"
    const val PER_APP_PROXY_LIST = "per_app_proxy_list"
    const val PER_APP_PROXY_MANAGED_MODE = "per_app_proxy_managed_mode"
    const val PER_APP_PROXY_MANAGED_LIST = "per_app_proxy_managed_list"
    const val PER_APP_PROXY_PACKAGE_QUERY_MODE = "per_app_proxy_package_query_mode"

    // Домены, которые идут мимо VPN. Хранятся локально и подмешиваются в конфиг при старте,
    // поэтому переживают обновление удалённого профиля-подписки.
    const val SPLIT_TUNNEL_DOMAINS = "split_tunnel_domains"
    const val SPLIT_TUNNEL_MODE = "split_tunnel_mode"

    // Выбор DNS-сервера: режим (auto/cloudflare/google/adguard/custom) и адрес для custom.
    const val DNS_MODE = "dns_mode"
    const val DNS_CUSTOM_SERVER = "dns_custom_server"

    // «Режим Россия»: российские сайты и приложения идут мимо VPN. ASKED — вопрос при первом
    // запуске уже задавали (чтобы не спрашивать повторно, каким бы ни был ответ).
    const val RUSSIA_MODE_ENABLED = "russia_mode_enabled"
    const val RUSSIA_MODE_ASKED = "russia_mode_asked"

    // Какие пакеты в список обхода добавил именно «Режим Россия». Нужно, чтобы при выключении
    // убрать ровно их и не тронуть то, что пользователь отметил сам.
    const val RUSSIA_MODE_ADDED_APPS = "russia_mode_added_apps"

    // Автоподключение: при открытии приложения и при загрузке телефона.
    const val AUTO_CONNECT_ON_APP_OPEN = "auto_connect_on_app_open"
    const val AUTO_START_ON_BOOT = "auto_start_on_boot"

    // Сторож соединения: проверяет, что трафик реально идёт, и чинит залипший туннель.
    const val WATCHDOG_ENABLED = "watchdog_enabled"
    const val WATCHDOG_LOG = "watchdog_log"

    const val ALLOW_BYPASS = "allow_bypass"
    const val SYSTEM_PROXY_ENABLED = "system_proxy_enabled"

    const val PRIVILEGE_SETTINGS_ENABLED = "hide_settings_enabled"
    const val PRIVILEGE_SETTINGS_LIST = "hide_settings_list"
    const val PRIVILEGE_SETTINGS_INTERFACE_RENAME_ENABLED = "hide_settings_interface_rename_enabled"
    const val PRIVILEGE_SETTINGS_INTERFACE_PREFIX = "hide_settings_interface_prefix"

    // OOM killer
    const val OOM_KILLER_ENABLED = "oom_killer_enabled"
    const val OOM_KILLER_DISABLED = "oom_killer_disabled"
    const val OOM_MEMORY_LIMIT_MB = "oom_memory_limit_mb"

    // dashboard
    const val DASHBOARD_ITEM_ORDER = "dashboard_item_order"
    const val DASHBOARD_DISABLED_ITEMS = "dashboard_disabled_items"

    // Remote Control
    const val ACTIVE_REMOTE_SERVER_ID = "active_remote_server_id"

    // Tailscale SSH
    const val TAILSCALE_SSH_REMEMBERED_USERNAMES = "tailscale_ssh_remembered_usernames"
    const val TAILSCALE_SSH_QUICK_CONNECT_PEERS = "tailscale_ssh_quick_connect_peers"
    const val TAILSCALE_SSH_LIGHT_THEME = "tailscale_ssh_light_theme"
    const val TAILSCALE_SSH_DARK_THEME = "tailscale_ssh_dark_theme"
    const val TAILSCALE_SSH_FONT_FAMILY = "tailscale_ssh_font_family"
    const val TAILSCALE_SSH_FONT_SIZE = "tailscale_ssh_font_size"
    const val TAILSCALE_SSH_CUSTOM_FONT_PATH = "tailscale_ssh_custom_font_path"

    // cache
    const val STARTED_BY_USER = "started_by_user"
    const val CACHED_UPDATE_INFO = "cached_update_info"
    const val CACHED_APK_PATH = "cached_apk_path"
    const val LAST_SHOWN_UPDATE_VERSION = "last_shown_update_version"
}
