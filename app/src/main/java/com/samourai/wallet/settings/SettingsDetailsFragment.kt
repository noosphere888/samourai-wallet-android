package com.samourai.wallet.settings

import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.preference.CheckBoxPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.transition.Transition
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.samourai.wallet.AboutActivity
import com.samourai.wallet.BuildConfig
import com.samourai.wallet.PayNymCalcActivity
import com.samourai.wallet.R
import com.samourai.wallet.SamouraiWallet
import com.samourai.wallet.access.AccessFactory
import com.samourai.wallet.constants.SamouraiAccountIndex
import com.samourai.wallet.constants.WALLET_INDEX
import com.samourai.wallet.crypto.AESUtil
import com.samourai.wallet.crypto.DecryptionException
import com.samourai.wallet.hd.HD_WalletFactory
import com.samourai.wallet.network.dojo.DojoUtil
import com.samourai.wallet.payload.ExternalBackupManager
import com.samourai.wallet.payload.ExternalBackupManager.askPermission
import com.samourai.wallet.payload.ExternalBackupManager.hasPermissions
import com.samourai.wallet.payload.PayloadUtil
import com.samourai.wallet.pin.PinChangeDialog
import com.samourai.wallet.pin.PinEntryDialog
import com.samourai.wallet.ricochet.RicochetMeta
import com.samourai.wallet.segwit.BIP49Util
import com.samourai.wallet.segwit.BIP84Util
import com.samourai.wallet.send.RBFUtil
import com.samourai.wallet.stealth.StealthModeSettings
import com.samourai.wallet.swaps.SwapsMeta
import com.samourai.wallet.tor.SamouraiTorManager
import com.samourai.wallet.util.CharSequenceX
import com.samourai.wallet.util.PrefsUtil
import com.samourai.wallet.util.QRBottomSheetDialog
import com.samourai.wallet.util.func.AddressFactory
import com.samourai.wallet.util.func.BatchSendUtil
import com.samourai.wallet.util.func.FormatsUtil
import com.samourai.wallet.util.func.SendAddressUtil
import com.samourai.wallet.util.tech.AppUtil
import com.samourai.wallet.util.tech.LogUtil
import com.samourai.wallet.whirlpool.WhirlpoolMeta
import com.samourai.wallet.whirlpool.service.WhirlpoolNotificationService
import com.samourai.whirlpool.client.utils.DebugUtils
import com.samourai.whirlpool.client.wallet.AndroidWhirlpoolWalletService
import com.samourai.whirlpool.client.wallet.beans.WhirlpoolUtxo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.apache.commons.io.FileUtils
import org.bitcoinj.crypto.MnemonicException.MnemonicLengthException
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Objects
import java.util.Objects.nonNull
import java.util.concurrent.CancellationException


class SettingsDetailsFragment(private val key: String?) : PreferenceFragmentCompat() {

    public var targetTransition: Transition? = null
    private var progress: ProgressDialog? = null
    private val scope = CoroutineScope(Dispatchers.IO) + SupervisorJob();

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        targetTransition?.addTarget(view)
        super.onViewCreated(view, savedInstanceState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        when (key) {
            "wallet" -> {
                setPreferencesFromResource(R.xml.settings_wallet, rootKey)
                activity?.title = "Settings | Wallet"
                walletSettings()
            }
            "txs" -> {
                activity?.title = "Settings | Transactions"
                setPreferencesFromResource(R.xml.settings_txs, rootKey)
                transactionsSettings()
            }
            "troubleshoot" -> {
                activity?.title = "Settings | Troubleshoot"
                setPreferencesFromResource(R.xml.settings_troubleshoot, rootKey)
                troubleShootSettings()
            }
            "other" -> {
                activity?.title = "Settings | Other"
                setPreferencesFromResource(R.xml.settings_other, rootKey)
                otherSettings()
            }
        }
    }

    private fun walletSettings() {

        val mnemonicPref = findPreference("mnemonic") as Preference?
        mnemonicPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getHDSeed(true)
            true
        }

        val stealthPref = findPreference("stealth") as Preference?
        stealthPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
             startActivity(Intent(this.activity,StealthModeSettings::class.java))
            true
        }

        val xpubPref = findPreference("xpub") as Preference?
        xpubPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(44, 0)
            true
        }

        val ypubPref = findPreference("ypub") as Preference?
        ypubPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(49, 0)
            true
        }

        val zpubPref = findPreference("zpub") as Preference?
        zpubPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, 0)
            true
        }

        val zpubPrePref = findPreference("zpub_pre") as Preference?
        zpubPrePref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, WhirlpoolMeta.getInstance(requireContext()).whirlpoolPremixAccount)
            true
        }

        val zpubPostPref = findPreference("zpub_post") as Preference?
        zpubPostPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, WhirlpoolMeta.getInstance(requireContext()).whirlpoolPostmix)
            true
        }

        val zpubPostXPref = findPreference("zpub_post_x") as Preference?
        zpubPostXPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(44, WhirlpoolMeta.getInstance(requireContext()).whirlpoolPostmix)
            true
        }

        val zpubPostYPref = findPreference("zpub_post_y") as Preference?
        zpubPostYPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(49, WhirlpoolMeta.getInstance(requireContext()).whirlpoolPostmix)
            true
        }

        val zpubBadBankPref = findPreference("zpub_badbank") as Preference?
        zpubBadBankPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, WhirlpoolMeta.getInstance(requireContext()).whirlpoolBadBank)
            true
        }

        val zpubSwapsMainPref = findPreference("zpub_swapsmain") as Preference?
        zpubSwapsMainPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, SwapsMeta.getInstance(requireContext()).swapsMainAccount)
            true
        }
        val zpubSwapsRefundPref = findPreference("zpub_swapsrefund") as Preference?
        zpubSwapsRefundPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, SwapsMeta.getInstance(requireContext()).swapsRefundAccount)
            true
        }
        val zpubSwapsAsbMainPref = findPreference("zpub_swapsasbmain") as Preference?
        zpubSwapsAsbMainPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            getXPUB(84, SwapsMeta.getInstance(requireContext()).swapsAsbMainAccount)
            true
        }

        val wipePref = findPreference("wipe") as Preference?
        wipePref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {

            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.sure_to_erase)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { dialog, whichButton ->
                        val progress = ProgressDialog(requireContext())
                        progress.setTitle(R.string.app_name)
                        progress.setMessage(requireContext().resources.getString(R.string.securely_wiping_wait))
                        progress.setCancelable(false)
                        progress.show()

                        WhirlpoolMeta.getInstance(requireContext().applicationContext).scode = null
                        WhirlpoolNotificationService.stopService(requireContext().applicationContext)
                        if (SamouraiTorManager.isConnected()) {
                            SamouraiTorManager.start()
                        }
                        scope.launch {
                            AppUtil.getInstance(requireContext()).wipeApp()

                            delay(500)
                            val walletDir = requireContext().getDir("wallet", Context.MODE_PRIVATE)
                            val filesDir = requireContext().filesDir
                            val cacheDir = requireContext().cacheDir

                            if (walletDir.exists()) {
                                FileUtils.deleteDirectory(walletDir);
                            }
                            if (filesDir.exists()) {
                                FileUtils.deleteDirectory(filesDir);
                            }
                            if (cacheDir.exists()) {
                                FileUtils.deleteDirectory(cacheDir);

                            }
                        }.invokeOnCompletion {
                            scope.launch(Dispatchers.Main) {
                                if (it == null) {
                                    if (progress.isShowing) {
                                        progress.dismiss();
                                    }
                                    Toast.makeText(requireContext(), R.string.wallet_erased, Toast.LENGTH_SHORT).show()
                                    AppUtil.getInstance(requireContext()).restartApp()
                                } else {
                                    if (progress.isShowing) {
                                        progress.dismiss();
                                    }
                                    Toast.makeText(requireContext(), "Error ${it.message}", Toast.LENGTH_SHORT).show()
                                    if (BuildConfig.DEBUG) {
                                        it.printStackTrace();
                                    }
                                }
                            }
                        }

                    }.setNegativeButton(R.string.cancel) { dialog, whichButton -> }.show()
            true
        }

        val cbPref5 = findPreference("scramblePin") as CheckBoxPreference?
        cbPref5!!.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            PrefsUtil.getInstance(requireContext())
                .setValue(PrefsUtil.SCRAMBLE_PIN, Objects.equals(newValue, true))
            true
        }

        val cbPref11 = findPreference("haptic") as CheckBoxPreference?
        cbPref11!!.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            PrefsUtil.getInstance(requireContext())
                .setValue(PrefsUtil.HAPTIC_PIN, Objects.equals(newValue, true))
            true
        }

        val changePinPref = findPreference("change_pin") as Preference?
        changePinPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.confirm_change_pin)
                    .setCancelable(false)
                    .setPositiveButton(R.string.yes) { dialog, whichButton ->

                        val pinEntryDialog = PinEntryDialog.create()
                        pinEntryDialog.setOnSuccessCallback {
                            requireActivity().runOnUiThread {
                                pinEntryDialog.dismiss()
                                val pinChangeDialog = PinChangeDialog.create()
                                pinChangeDialog.setOnSuccessCallback { newPin ->
                                    requireActivity().runOnUiThread {
                                        pinChangeDialog.dismiss()
                                        changeWalletPin(newPin)
                                    }
                                }
                                pinChangeDialog.show(requireActivity().supportFragmentManager, pinChangeDialog.tag)
                            }
                        }
                        pinEntryDialog.show(requireActivity().supportFragmentManager, pinEntryDialog.tag)

                    }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
            true
        }

        val cbPref6 = findPreference("autoBackup") as CheckBoxPreference?
        if (!SamouraiWallet.getInstance().hasPassphrase(requireContext())) {
            cbPref6!!.isChecked = false
            cbPref6.isEnabled = false
        } else {
            cbPref6!!.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
                if (cbPref6.isChecked) {
                    PrefsUtil.getInstance(requireContext()).setValue(PrefsUtil.AUTO_BACKUP, false)
                } else {
                    if(!ExternalBackupManager.hasPermissions()){
                        ExternalBackupManager.askPermission(requireActivity())
                    }
                    PrefsUtil.getInstance(requireContext()).setValue(PrefsUtil.AUTO_BACKUP, true)
                }
                true
            }
        }
    }

    private fun changeWalletPin(newPin: String?) {
        val accessHash = PrefsUtil.getInstance(requireContext()).getValue(PrefsUtil.ACCESS_HASH, "")
        val accessHash2 =
            PrefsUtil.getInstance(requireContext()).getValue(PrefsUtil.ACCESS_HASH2, "")
        val hash = AccessFactory.getInstance(requireContext()).getHash(
            AccessFactory.getInstance(requireContext()).guid,
            CharSequenceX(newPin),
            AESUtil.DefaultPBKDF2Iterations
        )
        PrefsUtil.getInstance(requireContext()).setValue(PrefsUtil.ACCESS_HASH, hash)
        if (accessHash == accessHash2) {
            PrefsUtil.getInstance(requireContext()).setValue(PrefsUtil.ACCESS_HASH2, hash)
        }
        AccessFactory.getInstance(requireContext()).pin = newPin
        try {
            PayloadUtil.getInstance(requireContext())
                .saveWalletToJSON(CharSequenceX(AccessFactory.getInstance(requireContext()).guid + newPin))
        } catch (e: Exception) {
            e.printStackTrace();
        } finally {
            Toast.makeText(
                requireContext().getApplicationContext(),
                R.string.success_change_pin,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun transactionsSettings() {

        val cbPref0 = findPreference("segwit") as CheckBoxPreference?
        cbPref0?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            if (cbPref0!!.isChecked) {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.USE_SEGWIT, false)
            } else {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.USE_SEGWIT, true)
            }
            true
        }

        val cbPref15 = findPreference("likeTypedChange") as CheckBoxPreference?
        cbPref15?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            if (cbPref15!!.isChecked) {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.USE_LIKE_TYPED_CHANGE, false)
            } else {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.USE_LIKE_TYPED_CHANGE, true)
            }
            true
        }

        val cbPref9 = findPreference("rbf") as CheckBoxPreference?
        cbPref9?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            if (cbPref9!!.isChecked) {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.RBF_OPT_IN, false)
            } else {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.RBF_OPT_IN, true)
            }
            true
        }

        val cbPref10 = findPreference("broadcastTx") as CheckBoxPreference?
        cbPref10?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            if (cbPref10!!.isChecked) {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.BROADCAST_TX, false)
            } else {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.BROADCAST_TX, true)
            }
            true
        }

        val cbPref11 = findPreference("strictOutputs") as CheckBoxPreference?
        cbPref11?.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { preference, newValue ->
            if (cbPref11!!.isChecked) {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.STRICT_OUTPUTS, false)
            } else {
                PrefsUtil.getInstance(activity).setValue(PrefsUtil.STRICT_OUTPUTS, true)
            }
            true
        }

    }

    private fun troubleShootSettings() {
        val troubleshootPref = findPreference("troubleshoot") as Preference?
        troubleshootPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            doTroubleshoot()
            true
        }

        val sendBackupPref = findPreference("send_backup_support") as Preference?
        sendBackupPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.prompt_send_backup_to_support)
                    .setCancelable(false)
                    .setPositiveButton(R.string.yes) { dialog, whichButton -> doSendBackup() }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
            true
        }

        val prunePref = findPreference("prune") as Preference?
        prunePref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val dlg = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.app_name)
                    .setMessage(R.string.prune_backup)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { dialog, whichButton -> doPrune() }
                    .setNegativeButton(R.string.cancel) { dialog, whichButton -> }
            if (!requireActivity().isFinishing()) {
                dlg.show()
            }
            true
        }

        val idxPref = findPreference("idx") as Preference?
        idxPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            doIndexes()
            true
        }

        val paynymCalcPref = findPreference("pcalc") as Preference?
        paynymCalcPref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            doPayNymCalc()
            true
        }

        val wpStatePref = findPreference("wpstate") as Preference?
        wpStatePref!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            doWhirlpoolState()
            true
        }

        val showlogsPrefs = findPreference("showlogs") as Preference?
        showlogsPrefs!!.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            showLogs()
            true
        }

    }

    private fun showLogs() {
        startActivity(Intent(requireContext(),LogViewActivity::class.java))
    }

    private fun otherSettings() {
        val aboutPref = findPreference("about") as Preference?
        aboutPref?.summary = "Samourai," + " " + BuildConfig.VERSION_NAME
        aboutPref?.onPreferenceClickListener = Preference.OnPreferenceClickListener {
            val intent = Intent(activity, AboutActivity::class.java)
            startActivity(intent)
            true
        }
    }

    private fun getHDSeed(mnemonic: Boolean) {
        var seed: String? = null
        try {
            seed = if (mnemonic) {
                HD_WalletFactory.getInstance(requireContext()).get().mnemonic
            } else {
                HD_WalletFactory.getInstance(requireContext()).get().seedHex
            }
        } catch (ioe: IOException) {
            ioe.printStackTrace()
            Toast.makeText(requireContext(), "HD wallet error", Toast.LENGTH_SHORT).show()
        } catch (mle: MnemonicLengthException) {
            mle.printStackTrace()
            Toast.makeText(requireContext(), "HD wallet error", Toast.LENGTH_SHORT).show()
        }
        val showText = TextView(requireContext())
        showText.text = seed
        showText.setTextIsSelectable(true)
        showText.setPadding(40, 10, 40, 10)
        showText.textSize = 18.0f
         MaterialAlertDialogBuilder(requireContext())
            .apply {
            }
                .setTitle(R.string.app_name)
                .setView(showText)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { dialog, _ ->
                    run {
                        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
                    }
                }.show().apply {
                    this.window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            }
    }

    private fun getXPUB(purpose: Int, account: Int) {
        var xpub = ""
        if((purpose == 44 || purpose == 49) && account == WhirlpoolMeta.getInstance(context).whirlpoolPostmix) {

            var vpub = BIP84Util.getInstance(requireContext()).wallet.getAccount(WhirlpoolMeta.getInstance(context).whirlpoolPostmix).zpubstr()

            if(purpose == 49) {
                xpub = FormatsUtil.xlatXPUB(vpub, true);
            }
            else {
                xpub = FormatsUtil.xlatXPUB(vpub, false);
            }

        }
        else {
            when (purpose) {
                49 -> xpub = BIP49Util.getInstance(requireContext()).wallet.getAccount(account).ypubstr()
                84 -> xpub = BIP84Util.getInstance(requireContext()).wallet.getAccount(account).zpubstr()
                else -> try {
                    xpub = HD_WalletFactory.getInstance(requireContext()).get().getAccount(account).xpubstr()
                } catch (ioe: IOException) {
                    ioe.printStackTrace()

                    Toast.makeText(requireContext(), "HD wallet error", Toast.LENGTH_SHORT).show()
                } catch (mle: MnemonicLengthException) {
                    mle.printStackTrace()
                    Toast.makeText(requireContext(), "HD wallet error", Toast.LENGTH_SHORT).show()
                }
            }
        }

        var dialogTitle = when (purpose) {
            44 -> "BIP44"
            84 -> "Segwit ZPUB"
            49 -> "Segwit YPUB"
            else -> "XPUB"
        }

        when (account) {
            SamouraiAccountIndex.POSTMIX -> {
                if(purpose == 49){
                    dialogTitle = "Whirlpool Post-mix YPUB"
                }
                else if(purpose == 44){
                    dialogTitle = "Whirlpool Post-mix XPUB"
                }
                else{
                    dialogTitle = "Whirlpool Post-mix ZPUB"
                }
            }
            SamouraiAccountIndex.PREMIX -> {
                dialogTitle = "Whirlpool Pre-mix ZPUB"
            }
            SamouraiAccountIndex.BADBANK -> {
                dialogTitle = "Whirlpool Bad bank ZPUB"
            }
            else -> dialogTitle
        }
        val dialog = QRBottomSheetDialog(
                qrData = xpub,
                dialogTitle, clipboardLabel = dialogTitle
        );
        dialog.show(requireActivity().supportFragmentManager, dialog.tag)
    }

    private fun doTroubleshoot() {
        try {
            if(!ExternalBackupManager.backupAvailable()){
                Toast.makeText(context, "Backup file is not available. please enable auto-backup to continue", Toast.LENGTH_SHORT).show()
                return
            }
            val strExpected = HD_WalletFactory.getInstance(requireContext()).get().passphrase
            val view = layoutInflater.inflate(R.layout.password_input_dialog_layout, null)
            val password = view.findViewById<EditText>(R.id.restore_dialog_password_edittext)
            val message = view.findViewById<TextView>(R.id.dialogMessage)
            message.text = getString(R.string.wallet_passphrase);
            val dlg = MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.app_name)
                    .setView(view)
                    .setCancelable(false)
                    .setPositiveButton(R.string.ok) { dialog, whichButton ->
                        val _passphrase39 = password.text.toString()
                        if (_passphrase39 == strExpected) {
                            Toast.makeText(requireContext(), R.string.bip39_match, Toast.LENGTH_SHORT).show()
                            if (ExternalBackupManager.backupAvailable()) {
                                MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(R.string.app_name)
                                        .setMessage(R.string.bip39_decrypt_test)
                                        .setCancelable(false)
                                        .setPositiveButton(R.string.yes) { dialog, whichButton ->
                                            scope.launch(Dispatchers.IO) {
                                               val data =  ExternalBackupManager.read()
                                                val decrypted = PayloadUtil.getInstance(requireContext()).getDecryptedBackupPayload(data, CharSequenceX(_passphrase39))
                                                withContext(Dispatchers.Main){
                                                    if (decrypted == null || decrypted.isEmpty()) {
                                                        Toast.makeText(requireContext(), R.string.backup_read_error, Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(requireContext(), R.string.backup_read_ok, Toast.LENGTH_SHORT).show()
                                                    }
                                                }
                                            }

                                        }.setNegativeButton(R.string.no) { dialog, whichButton -> }.show()
                            }
                        } else {
                            Toast.makeText(requireContext(), R.string.invalid_passphrase, Toast.LENGTH_SHORT).show()
                        }
                    }.setNegativeButton(R.string.cancel) { dialog, whichButton -> }
            if (!requireActivity().isFinishing()) {
                dlg.show()
            }
        } catch (ioe: IOException) {
            ioe.printStackTrace()
            Toast.makeText(requireContext(), "HD wallet error", Toast.LENGTH_SHORT).show()
        } catch (mle: MnemonicLengthException) {
            mle.printStackTrace()
            Toast.makeText(requireContext(), "HD wallet error", Toast.LENGTH_SHORT).show()
        }
    }

    private fun doPrune() {
        if (!hasPermissions()) {
            askPermission(requireActivity())
            ExternalBackupManager.getPermissionStateLiveData().observe(this.viewLifecycleOwner, {
                if (it) {
                    doPrune()
                }
            })
        }
        else {
            try {
//                      BIP47Meta.getInstance().pruneIncoming();
                SendAddressUtil.getInstance().reset()
                RicochetMeta.getInstance(requireContext()).empty()
                BatchSendUtil.getInstance().clear()
                RBFUtil.getInstance().clear()
                PayloadUtil.getInstance(requireContext()).saveWalletToJSON(CharSequenceX(AccessFactory.getInstance(requireContext()).guid + AccessFactory.getInstance(requireContext()).pin))
            } catch (je: JSONException) {
                je.printStackTrace()
                Toast.makeText(requireContext(), R.string.error_reading_payload, Toast.LENGTH_SHORT).show()
            } catch (mle: MnemonicLengthException) {
            } catch (ioe: IOException) {
            } catch (de: DecryptionException) {
            }
        }
    }

    private fun doSendBackup() {

        try {

            val jsonObject = PayloadUtil.getInstance(requireContext()).payload

            jsonObject.getJSONObject("wallet").remove("seed")
            jsonObject.getJSONObject("wallet").remove("passphrase")

            if (jsonObject.has("meta")) {

                if (jsonObject.getJSONObject("meta").has("pin")) {
                    jsonObject.getJSONObject("meta").remove("pin")
                }
                if (jsonObject.getJSONObject("meta").has("pin2")) {
                    jsonObject.getJSONObject("meta").remove("pin2")
                }

                if (jsonObject.getJSONObject("meta").has("trusted_node")) {
                    if (jsonObject.getJSONObject("meta").getJSONObject("trusted_node").has("password")) {
                        jsonObject.getJSONObject("meta").getJSONObject("trusted_node").remove("password")
                    }
                    if (jsonObject.getJSONObject("meta").getJSONObject("trusted_node").has("node")) {
                        jsonObject.getJSONObject("meta").getJSONObject("trusted_node").remove("node")
                    }
                    if (jsonObject.getJSONObject("meta").getJSONObject("trusted_node").has("port")) {
                        jsonObject.getJSONObject("meta").getJSONObject("trusted_node").remove("port")
                    }
                    if (jsonObject.getJSONObject("meta").getJSONObject("trusted_node").has("user")) {
                        jsonObject.getJSONObject("meta").getJSONObject("trusted_node").remove("user")
                    }
                }
            }

            if (displayMailToSend(jsonObject, "message/rfc822")) return
            if (displayMailToSend(jsonObject, "text/plain")) return
            if (displayMailToSendTo(jsonObject)) return
            displayInAlertDialog(jsonObject);

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), R.string.error_reading_payload, Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayInAlertDialog(jsonObject: JSONObject) {

        val emailAddress = "help@samourai.support"
        val emailSubject = "Samourai Wallet support backup"
        val emailBody = jsonObject.toString()

        val emailContent = "Email : $emailAddress\n\nSubjet : $emailSubject\n\nBody : $emailBody"

        AlertDialog.Builder(requireContext())
            .setTitle(requireContext().getText(R.string.no_messaging_app_installed))
            .setMessage(emailContent)
            .setPositiveButton(requireContext().getText(R.string.copy_content)) { _, _ ->
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("mail content", emailContent)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(requireContext(), requireContext().getText(R.string.email_content_copied), Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Cancel") { _, _ -> }
            .show()
    }

    private fun displayMailToSend(jsonObject: JSONObject, type:String) : Boolean {
        val email = Intent(Intent.ACTION_SEND)
        email.putExtra(Intent.EXTRA_EMAIL, arrayOf("help@samourai.support"))
        email.putExtra(Intent.EXTRA_SUBJECT, "Samourai Wallet support backup")
        email.putExtra(Intent.EXTRA_TEXT, jsonObject.toString())
        email.type = type

        val chooser =
            Intent.createChooser(email, requireContext().getText(R.string.choose_email_client))

        if (nonNull(email.resolveActivity(requireContext().packageManager))) {
            startActivity(chooser)
            return true
        }
        return false
    }

    private fun displayMailToSendTo(jsonObject: JSONObject) : Boolean {
        val email = Intent(Intent.ACTION_SENDTO)
        email.data = Uri.parse("mailto:help@samourai.support")
        email.putExtra(Intent.EXTRA_SUBJECT, "Samourai Wallet support backup")
        email.putExtra(Intent.EXTRA_TEXT, jsonObject.toString())

        if (email.resolveActivity(requireContext().packageManager) != null) {
            startActivity(email)
            return true
        }
        return false
    }

    private fun doIndexes() {
        val builder = StringBuilder()
        builder.append("highestIdx ; walletIdx ; hdIdx => index\n");

        for (walletIndex in WALLET_INDEX.values()) {
            var debugIndex = AddressFactory.getInstance(requireContext()).debugIndex(walletIndex);
            builder.append("$walletIndex: $debugIndex\n")
        }
        builder.append("""
    Ricochet :${RicochetMeta.getInstance(requireContext()).index}
    
    """.trimIndent())

        // debug consistency
        val debugConsistency = AddressFactory.getInstance(context).debugConsistency()
        builder.append("\n-- AddressFactory consistency --\n"+debugConsistency)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.app_name)
            .setMessage(builder.toString())
            .setCancelable(false)
            .setPositiveButton(R.string.ok) { dialog, whichButton -> dialog.dismiss() }
            .show()

        LogUtil.debugLarge("Settings", "# INDEXES DEBUG #\n"+builder.toString());
    }

    private fun doPayNymCalc() {
        val intent = Intent(requireContext(), PayNymCalcActivity::class.java)
        startActivity(intent)
    }

    private fun utxoToString(whirlpoolUtxo: WhirlpoolUtxo): String {
        val builder = StringBuilder()
        val utxo = whirlpoolUtxo.utxo
        builder.append("[").append(utxo.tx_hash).append(":").append(utxo.tx_output_n).append("] ").append(utxo.value.toString() + "sats").append(", ").append(utxo.confirmations).append("confs")
        builder.append(", ").append(if (whirlpoolUtxo.utxoState.poolId != null) whirlpoolUtxo.utxoState.poolId else "no pool")
        builder.append(", ").append(whirlpoolUtxo.mixsDone.toString()).append(" mixed")
        builder.append(", ").append(whirlpoolUtxo.account).append(", ").append(whirlpoolUtxo.utxo.path)
        builder.append(", ").append(whirlpoolUtxo.utxoState)
        return builder.toString()
    }

    private fun doWhirlpoolState() {
        val whirlpoolWalletService = AndroidWhirlpoolWalletService.getInstance()
        val whirlpoolWallet = whirlpoolWalletService.whirlpoolWallet()
        val debugInfo = DebugUtils.getDebug(whirlpoolWallet)

        MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_name)
                .setMessage(debugInfo)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { dialog, whichButton -> dialog.dismiss() }
                .show()

        LogUtil.debugLarge("Settings", "# WHIRLPOOL DEBUG #\n"+debugInfo);
    }

    private fun doPSBT() {
        val edPSBT = EditText(requireContext())
        edPSBT.isSingleLine = false
        edPSBT.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        edPSBT.setLines(10)
        edPSBT.setHint(R.string.PSBT)
        edPSBT.gravity = Gravity.START
        val textWatcher: TextWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable) {
                edPSBT.setSelection(0)
            }

            override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            }

            override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            }
        }
        edPSBT.addTextChangedListener(textWatcher)
        val dlg = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.app_name)
                .setView(edPSBT)
                .setMessage(R.string.enter_psbt)
                .setCancelable(false)
                .setPositiveButton(R.string.ok) { dialog, whichButton ->
                    dialog.dismiss()
                    val strPSBT = edPSBT.text.toString().replace(" ".toRegex(), "").trim { it <= ' ' }
                    try {
                        com.samourai.wallet.psbt.PSBTUtil.getInstance(requireContext()).doPSBT(strPSBT)
                    } catch (e: Exception) {
                    }
                }.setNegativeButton(R.string.cancel) { dialog, whichButton -> dialog.dismiss() }
        if (!requireActivity().isFinishing()) {
            dlg.show()
        }
    }


    override fun onDestroy() {
        if (scope.isActive) {
            scope.cancel(CancellationException())
        }
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        ExternalBackupManager.onActivityResult(requestCode, resultCode, data, requireActivity().application)
        super.onActivityResult(requestCode, resultCode, data)

    }
}