package com.euler.catalog

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import dadb.AdbKeyPair
import dadb.Dadb
import java.io.File

/**
 * Shell com permissão de ADB, sem notebook.
 *
 * O app se conecta ao ADB do PRÓPRIO carro (127.0.0.1:5555) como cliente ADB — exatamente
 * o que outros apps de cluster fazem. Na primeira conexão o carro mostra "Permitir depuração
 * USB?"; ao tocar em "Sempre permitir", o app passa a ter shell e a chave fica autorizada.
 *
 * Tudo aqui é OPCIONAL. A loja funciona sem isto. Só é acionado quando o painel pede
 * (ex.: Setar Device Owner) ou, no futuro, para a função de cluster. Se a depuração não
 * estiver disponível (ex.: Sealion), falha de forma limpa, sem travar nada.
 */
object AdbShell {

    const val HOST = "127.0.0.1"
    const val PORT = 5555

    data class Result(val ok: Boolean, val output: String, val reason: String = "")

    private fun admin(ctx: Context) = ComponentName(ctx, CatalogAdminReceiver::class.java)

    /** true se este app já é Device Owner. Não precisa de ADB para saber. */
    fun isDeviceOwner(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
            .isDeviceOwnerApp(ctx.packageName)
    } catch (e: Exception) { false }

    /** Chave ADB do app (gerada uma vez e reaproveitada). É ela que o carro memoriza no "Sempre permitir". */
    private fun keyPair(ctx: Context): AdbKeyPair {
        val priv = File(ctx.filesDir, "adbkey")
        val pub = File(ctx.filesDir, "adbkey.pub")
        if (!priv.exists() || !pub.exists()) AdbKeyPair.generate(priv, pub)
        return AdbKeyPair.read(priv, pub)
    }

    /**
     * Roda um comando no shell do carro via ADB local.
     * O timeout é generoso de propósito: na primeira vez, dá tempo de tocar em "Sempre permitir".
     */
    fun run(ctx: Context, command: String, timeoutMs: Int = 45000): Result {
        var dadb: Dadb? = null
        return try {
            dadb = Dadb.create(HOST, PORT, keyPair(ctx), 4000, timeoutMs)
            val r = dadb.shell(command)
            val out = (r.output + r.errorOutput).trim()
            Result(r.exitCode == 0, out, if (r.exitCode == 0) "" else "exit ${r.exitCode}")
        } catch (e: Exception) {
            Result(false, "", adbError(e))
        } finally {
            try { dadb?.close() } catch (_: Exception) {}
        }
    }

    private fun adbError(e: Exception): String {
        val m = (e.message ?: e.javaClass.simpleName)
        return when {
            m.contains("refused", true) || m.contains("ECONNREFUSED", true) ->
                "ADB não está escutando na rede (depuração por TCP desligada)"
            m.contains("timeout", true) || m.contains("timed out", true) ->
                "sem resposta do ADB (autorização não concedida no carro?)"
            m.contains("reset", true) || m.contains("closed", true) ->
                "conexão ADB recusada (autorização negada?)"
            else -> m.take(160)
        }
    }

    /**
     * Define este app como Device Owner via ADB.
     * Requer depuração ligada e o carro sem contas/usuários extras.
     * Confere pelo estado real do sistema, não só pela saída do comando.
     */
    fun setDeviceOwner(ctx: Context): Result {
        if (isDeviceOwner(ctx)) return Result(true, "já era Device Owner")
        val comp = admin(ctx).flattenToShortString() // com.euler.catalog/.CatalogAdminReceiver
        val r = run(ctx, "dpm set-device-owner $comp")
        if (isDeviceOwner(ctx)) return Result(true, r.output.ifBlank { "Success" })
        // Não virou: se nem o shell respondeu, o motivo é o do ADB; senão, interpreta a saída do dpm.
        val reason = if (r.output.isBlank() && r.reason.isNotBlank()) r.reason else parseDpmError(r.output)
        return Result(false, r.output, reason)
    }

    private fun parseDpmError(out: String): String = when {
        out.contains("already", true) && out.contains("owner", true) -> "já existe um owner/admin definido"
        out.contains("account", true) -> "há conta(s) cadastradas no carro — remova antes"
        out.contains("users", true) -> "há mais de um usuário no carro"
        out.isBlank() -> "falha ao definir Device Owner"
        else -> out.take(160)
    }
}
