package com.euler.catalog

import android.app.admin.DeviceAdminReceiver

/**
 * Receiver de administração do dispositivo.
 * É o componente que o "dpm set-device-owner" aponta e que dá ao app o poder de
 * instalar silenciosamente e conceder permissões.
 */
class CatalogAdminReceiver : DeviceAdminReceiver()
