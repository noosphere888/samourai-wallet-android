package com.samourai.wallet.send;

import static com.samourai.wallet.util.tech.LogUtil.debug;
import static java.util.Objects.isNull;

import android.content.Context;
import android.widget.Toast;

import com.samourai.wallet.R;
import com.samourai.wallet.SamouraiWallet;
import com.samourai.wallet.api.APIFactory;
import com.samourai.wallet.bip47.BIP47Meta;
import com.samourai.wallet.bip47.BIP47Util;
import com.samourai.wallet.bip47.rpc.PaymentCode;
import com.samourai.wallet.bipFormat.BIP_FORMAT;
import com.samourai.wallet.bipFormat.BipFormat;
import com.samourai.wallet.constants.WALLET_INDEX;
import com.samourai.wallet.hd.HD_Address;
import com.samourai.wallet.network.dojo.DojoUtil;
import com.samourai.wallet.segwit.BIP49Util;
import com.samourai.wallet.segwit.BIP84Util;
import com.samourai.wallet.segwit.bech32.Bech32Util;
import com.samourai.wallet.send.exceptions.SignTxException;
import com.samourai.wallet.util.FormatsUtilGeneric;
import com.samourai.wallet.util.PrefsUtil;
import com.samourai.wallet.util.func.AddressFactory;
import com.samourai.wallet.util.func.FormatsUtil;
import com.samourai.wallet.whirlpool.WhirlpoolMeta;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.bitcoinj.core.Address;
import org.bitcoinj.core.Coin;
import org.bitcoinj.core.DumpedPrivateKey;
import org.bitcoinj.core.ECKey;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionInput;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.script.Script;
import org.bitcoinj.script.ScriptBuilder;
import org.bitcoinj.script.ScriptException;
import org.bouncycastle.util.encoders.Hex;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Vector;

//import android.util.Log;

public class SendFactory extends SendFactoryGeneric	{

    private static SendFactory instance = null;
    private static Context context = null;

    private SendFactory () { ; }

    public static SendFactory getInstance(Context ctx) {

        context = ctx;

        if(instance == null)	{
            instance = new SendFactory();
        }

        return instance;
    }

    public Transaction makeTransaction(final List<MyTransactionOutPoint> unspent, final Map<String, BigInteger> receivers) {

        Transaction tx = null;

        try {
//            int changeIdx = HD_WalletFactory.getInstance(context).get().getAccount(accountIdx).getChange().getAddrIdx();
            boolean rbfOptin = PrefsUtil.getInstance(context).getValue(PrefsUtil.RBF_OPT_IN, false);
            long blockHeight = APIFactory.getInstance(context).getLatestBlockHeight();
            NetworkParameters params = SamouraiWallet.getInstance().getCurrentNetworkParams();
            tx = super.makeTransaction(unspent, receivers, BIP_FORMAT.PROVIDER, rbfOptin, params, blockHeight);
        }
        catch(Exception e) {
            e.printStackTrace();
        }

        return tx;
    }

    public Transaction signTransaction(Transaction unsignedTx, int account)  {

        if (isNull(unsignedTx)) return null;

        final Map<String,ECKey> keyBag = new HashMap<>();

        for (TransactionInput input : CollectionUtils.emptyIfNull(unsignedTx.getInputs())) {

            try {
                byte[] scriptBytes = input.getOutpoint().getConnectedPubKeyScript();
                String address = null;
//                Log.i("SendFactory", "connected pubkey script:" + Hex.toHexString(scriptBytes));
                if(Bech32Util.getInstance().isBech32Script(Hex.toHexString(scriptBytes)))    {
                    address = Bech32Util.getInstance().getAddressFromScript(Hex.toHexString(scriptBytes));
                }
                else    {
                    address = new Script(scriptBytes).getToAddress(SamouraiWallet.getInstance().getCurrentNetworkParams()).toString();
                }
//                Log.i("SendFactory", "address from script:" + address);
                ECKey ecKey = null;
                ecKey = getPrivKey(address, account);
                if(ecKey != null) {
                    keyBag.put(input.getOutpoint().toString(), ecKey);
                }
                else {
                    throw new RuntimeException("ECKey error: cannot process private key");
//                    Log.i("ECKey error", "cannot process private key");
                }
            }
            catch(ScriptException se) {
                ;
            }
            catch(Exception e) {
                ;
            }

        }

        Transaction signedTx = null;
        try {
            signedTx = signTransaction(unsignedTx, keyBag);
        } catch (SignTxException e) {
            e.printStackTrace();
        }
        if(signedTx == null)    {
            return null;
        }
        else    {
            String hexString = new String(Hex.encode(signedTx.bitcoinSerialize()));
            if(hexString.length() > (100 * 1024)) {
                Toast.makeText(context, R.string.tx_length_error, Toast.LENGTH_SHORT).show();
//              Log.i("SendFactory", "Transaction length too long");
            }

            return signedTx;
        }
    }

    private synchronized Transaction signTransaction(Transaction transaction, HashMap<String,ECKey> keyBag) throws SignTxException {
        return super.signTransaction(transaction, keyBag);
    }

    public Pair<List<MyTransactionOutPoint>, List<TransactionOutput>> boltzmann(List<UTXO> utxos, List<UTXO> utxosBis, BigInteger spendAmount, String address, int account) {

        Triple<ArrayList<MyTransactionOutPoint>, ArrayList<TransactionOutput>, ArrayList<UTXO>> set0 = boltzmannSet(utxos, spendAmount, address, null, account, null);
        if(set0 == null)    {
            return null;
        }
        debug("SendFactory", "set0 utxo returned:" + set0.getRight().toString());

        long set0Value = 0L;
        for(UTXO u : set0.getRight())   {
            set0Value += u.getValue();
        }

        long utxosBisValue = 0L;
        if(utxosBis != null)    {
            for(UTXO u : utxosBis)   {
                utxosBisValue += u.getValue();
            }
        }

        debug("SendFactory", "set0 value:" + set0Value);
        debug("SendFactory", "utxosBis value:" + utxosBisValue);

        List<UTXO> _utxo = null;
        if(set0.getRight() != null && set0.getRight().size() > 0 && set0Value > spendAmount.longValue())    {
            debug("SendFactory", "set0 selected for 2nd pass");
            _utxo = set0.getRight();
        }
        else if(utxosBis != null && utxosBisValue > spendAmount.longValue())   {
            debug("SendFactory", "utxosBis selected for 2nd pass");
            _utxo = utxosBis;
        }
        else    {
            return null;
        }
        Triple<ArrayList<MyTransactionOutPoint>, ArrayList<TransactionOutput>, ArrayList<UTXO>> set1 = boltzmannSet(_utxo, spendAmount, address, set0.getLeft(), account, set0.getMiddle());
        if(set1 == null)    {
            return null;
        }

        Pair<List<MyTransactionOutPoint>, List<TransactionOutput>> ret = Pair.of(new ArrayList<>(), new ArrayList<>());

        ret.getLeft().addAll(set0.getLeft());
        ret.getLeft().addAll(set1.getLeft());
//        ret.getRight().addAll(set0.getMiddle());
        ret.getRight().addAll(set1.getMiddle());

        return ret;
    }

    public static Triple<ArrayList<MyTransactionOutPoint>, ArrayList<TransactionOutput>, ArrayList<UTXO>> boltzmannSet(List<UTXO> utxos, BigInteger spendAmount, String address, List<MyTransactionOutPoint> firstPassOutpoints, int account, List<TransactionOutput> outputs0) {

        if(utxos == null || utxos.size() == 0)    {
            return null;
        }

        List<String> seenPreviousSetHash = null;
        if(firstPassOutpoints != null)    {
            seenPreviousSetHash = new ArrayList<>();

            for(MyTransactionOutPoint outpoint : firstPassOutpoints)   {
                seenPreviousSetHash.add(outpoint.getTxHash().toString());
            }
        }

        int countP2WSH_P2TR = 0;
        if(FormatsUtilGeneric.getInstance().isValidP2WSH_P2TR(address))    {
            countP2WSH_P2TR = 1;
        }

        int changeType = 84;
        int mixedType = 84;
        boolean useLikeType = PrefsUtil.getInstance(context).getValue(PrefsUtil.USE_LIKE_TYPED_CHANGE, true);
        if (DojoUtil.getInstance(context).getDojoParams() != null && !DojoUtil.getInstance(context).isLikeType()) {
            useLikeType = false;
        }
        if(useLikeType)    {
            //
            // inputs are pre-grouped by type
            // type of address for change must match type of address for inputs
            //
            String utxoAddress = utxos.get(0).getOutpoints().get(0).getAddress();
            if(FormatsUtil.getInstance().isValidBech32(utxoAddress))    {
                changeType = 84;
            }
            else if(Address.fromBase58(SamouraiWallet.getInstance().getCurrentNetworkParams(), utxoAddress).isP2SHAddress())   {
                changeType = 49;
            }
            else    {
                changeType = 44;
            }

            //
            // type of address for 'mixed' amount must match type of address for destination
            //
            if(FormatsUtil.getInstance().isValidBech32(address))    {
                mixedType = 84;
            }
            else if(Address.fromBase58(SamouraiWallet.getInstance().getCurrentNetworkParams(), address).isP2SHAddress())   {
                mixedType = 49;
            }
            else    {
                mixedType = 44;
            }
        }

        Triple<Integer,Integer,Integer> firstPassOutpointTypes = null;
        if(firstPassOutpoints != null)    {
            firstPassOutpointTypes = FeeUtil.getInstance().getOutpointCount(new Vector<>(firstPassOutpoints));
        }
        else    {
            firstPassOutpointTypes = Triple.of(0, 0, 0);
        }

        long totalOutpointsAmount = 0L;
        for(UTXO utxo : utxos)   {
            totalOutpointsAmount += utxo.getValue();
        }
        debug("SendFactory", "total outputs amount:" + totalOutpointsAmount);
        debug("SendFactory", "spend amount:" + spendAmount.toString());
        debug("SendFactory", "utxos:" + utxos.size());

        if(totalOutpointsAmount <= spendAmount.longValue())    {
            debug("SendFactory", "spend amount must be > total amount available");
            return null;
        }

        List<MyTransactionOutPoint> selectedOutpoints = new ArrayList<>();
        BigInteger selectedValue = BigInteger.ZERO;
        BigInteger biFee = BigInteger.ZERO;
        List<TransactionOutput> txOutputs = new ArrayList<>();
        TransactionOutput txSpendOutput;
        TransactionOutput txChangeOutput;
        Script outputScript;
        String changeAddress;
        HashMap<String,MyTransactionOutPoint> seenOutpoints = new HashMap<>();
        List<MyTransactionOutPoint> recycleOutPoints = new ArrayList<>();
        List<UTXO> recycleUTXOs = new ArrayList<>();

        BigInteger bDust = firstPassOutpoints == null ? BigInteger.ZERO : SamouraiWallet.bDust;

        // select utxos until > spendAmount * 2
        // create additional change output(s)
        int idx = 0;
        for (int i = 0; i < utxos.size(); i++) {

            UTXO utxo = utxos.get(i);

            boolean utxoIsSelected = false;

            recycleOutPoints.clear();

            for(MyTransactionOutPoint op : utxo.getOutpoints())   {
                String hash = op.getTxHash().toString();
                if(seenPreviousSetHash != null && seenPreviousSetHash.contains(hash))    {
                    ;
                }
                else if(!seenOutpoints.containsKey(hash))    {
                    seenOutpoints.put(hash,op);
                    selectedValue = selectedValue.add(BigInteger.valueOf(op.getValue().longValue()));
                    debug("SendFactory", "selected:" + i + "," + op.getTxHash().toString() + "," + op.getValue().longValue());
                    utxoIsSelected = true;
                }
                else if(op.getValue().longValue() > seenOutpoints.get(hash).getValue().longValue()) {
                    recycleOutPoints.add(seenOutpoints.get(hash));
                    seenOutpoints.put(hash,op);
                    selectedValue = selectedValue.subtract(BigInteger.valueOf(seenOutpoints.get(hash).getValue().longValue()));
                    selectedValue = selectedValue.add(BigInteger.valueOf(op.getValue().longValue()));
                    debug("SendFactory", "selected (replace):"+ i + "," + op.getTxHash().toString() + "," + op.getValue().longValue());
                    utxoIsSelected = true;
                }
                else    {
                    ;
                }

                selectedOutpoints.clear();
                selectedOutpoints.addAll(seenOutpoints.values());
            }

            if(recycleOutPoints.size() > 0)    {
                UTXO recycleUTXO = new UTXO();
                recycleUTXO.setOutpoints(recycleOutPoints);
                recycleUTXOs.add(recycleUTXO);
            }

            if(utxoIsSelected)    {
                idx++;
            }

            if(firstPassOutpoints != null)    {
                Triple<Integer,Integer,Integer> outputTypes = FeeUtil.getInstance().getOutpointCount(new Vector<>(selectedOutpoints));
                biFee = FeeUtil.getInstance().estimatedFeeSegwit(firstPassOutpointTypes.getLeft() + outputTypes.getLeft(), firstPassOutpointTypes.getMiddle() + outputTypes.getMiddle(), firstPassOutpointTypes.getRight() + outputTypes.getRight(), 4 - countP2WSH_P2TR, countP2WSH_P2TR);
            }

            if(selectedValue.compareTo(spendAmount.add(biFee).add(bDust)) > 0)    {
                break;
            }

        }

        if(selectedValue.compareTo(spendAmount.add(biFee).add(bDust)) <= 0)    {
            return null;
        }

        List<MyTransactionOutPoint> _selectedOutpoints = new ArrayList<>();
        Collections.sort(selectedOutpoints, new UTXO.OutpointComparator());
        long _value = 0L;
        for(MyTransactionOutPoint op : selectedOutpoints)   {
            _selectedOutpoints.add(op);
            _value += op.getValue().longValue();
            if(firstPassOutpoints != null)    {
                Triple<Integer,Integer,Integer> outputTypes = FeeUtil.getInstance().getOutpointCount(new Vector<>(_selectedOutpoints));
                biFee = FeeUtil.getInstance().estimatedFeeSegwit(firstPassOutpointTypes.getLeft() + outputTypes.getLeft(), firstPassOutpointTypes.getMiddle() + outputTypes.getMiddle(), firstPassOutpointTypes.getRight() + outputTypes.getRight(), 4 - countP2WSH_P2TR, countP2WSH_P2TR);
            }
            if(_value > spendAmount.add(biFee).add(bDust).longValue())    {
                break;
            }
        }
        selectedValue = BigInteger.valueOf(_value);
        selectedOutpoints.clear();
        selectedOutpoints.addAll(_selectedOutpoints);

        debug("SendFactory", "utxos idx:" + idx);

        List<UTXO> _utxos = new ArrayList<>(utxos.subList(idx, utxos.size()));
        debug("SendFactory", "utxos after selection:" + _utxos.size());
        _utxos.addAll(recycleUTXOs);
        debug("SendFactory", "utxos after adding recycled:" + _utxos.size());
        BigInteger changeDue = selectedValue.subtract(spendAmount);

        if(firstPassOutpoints != null)    {
            Triple<Integer,Integer,Integer> outputTypes = FeeUtil.getInstance().getOutpointCount(new Vector<>(selectedOutpoints));
            biFee = FeeUtil.getInstance().estimatedFeeSegwit(firstPassOutpointTypes.getLeft() + outputTypes.getLeft(), firstPassOutpointTypes.getMiddle() + outputTypes.getMiddle(), firstPassOutpointTypes.getRight() + outputTypes.getRight(), 4 - countP2WSH_P2TR, countP2WSH_P2TR);
            debug("SendFactory", "biFee:" + biFee.toString());
            if(biFee.mod(BigInteger.valueOf(2L)).compareTo(BigInteger.ZERO) != 0)    {
                biFee = biFee.add(BigInteger.ONE);
            }
            debug("SendFactory", "biFee pair:" + biFee.toString());
        }

        if(changeDue.subtract(biFee.divide(BigInteger.valueOf(2L))).compareTo(SamouraiWallet.bDust) > 0)    {
            changeDue = changeDue.subtract(biFee.divide(BigInteger.valueOf(2L)));
            debug("SendFactory", "fee set1:" + biFee.divide(BigInteger.valueOf(2L)).toString());
        }
        else    {
            return null;
        }

        if(outputs0 != null && outputs0.size() == 2)    {
            TransactionOutput changeOutput0 = outputs0.get(1);
            BigInteger changeDue0 = BigInteger.valueOf(changeOutput0.getValue().longValue());
            if(changeDue0.subtract(biFee.divide(BigInteger.valueOf(2L))).compareTo(SamouraiWallet.bDust) > 0)    {
                changeDue0 = changeDue0.subtract(biFee.divide(BigInteger.valueOf(2L)));
                debug("SendFactory", "fee set0:" + biFee.divide(BigInteger.valueOf(2L)).toString());
            }
            else    {
                return null;
            }
            changeOutput0.setValue(Coin.valueOf(changeDue0.longValue()));
            outputs0.set(1, changeOutput0);
        }

        try {

            String _address = null;
            if(firstPassOutpoints == null)    {
                _address = address;
            }
            else    {
                _address = getChangeAddress(mixedType, account);
            }
            if(FormatsUtil.getInstance().isValidBech32(_address))   {
                txSpendOutput = Bech32Util.getInstance().getTransactionOutput(_address, spendAmount.longValue());
            }
            else    {
                outputScript = ScriptBuilder.createOutputScript(org.bitcoinj.core.Address.fromBase58(SamouraiWallet.getInstance().getCurrentNetworkParams(), _address));
                txSpendOutput = new TransactionOutput(SamouraiWallet.getInstance().getCurrentNetworkParams(), null, Coin.valueOf(spendAmount.longValue()), outputScript.getProgram());
            }
            txOutputs.add(txSpendOutput);

            changeAddress = getChangeAddress(changeType, account);
            if(FormatsUtil.getInstance().isValidBech32(changeAddress))    {
                txChangeOutput = Bech32Util.getInstance().getTransactionOutput(changeAddress, changeDue.longValue());
            }
            else    {
                outputScript = ScriptBuilder.createOutputScript(org.bitcoinj.core.Address.fromBase58(SamouraiWallet.getInstance().getCurrentNetworkParams(), changeAddress));
                txChangeOutput = new TransactionOutput(SamouraiWallet.getInstance().getCurrentNetworkParams(), null, Coin.valueOf(changeDue.longValue()), outputScript.getProgram());
            }
            txOutputs.add(txChangeOutput);
        }
        catch(Exception e) {
            return null;
        }

        long inValue = 0L;
        for(MyTransactionOutPoint outpoint : selectedOutpoints)   {
            inValue += outpoint.getValue().longValue();
            debug("SendFactory", "input:" + outpoint.getTxHash().toString() + "-" + outpoint.getTxOutputN() + "," + outpoint.getValue().longValue());
        }
        long outValue = 0L;
        for(TransactionOutput tOut : txOutputs)   {
            outValue += tOut.getValue().longValue();
            debug("SendFactory", "output:" + tOut.toString() + "," + tOut.getValue().longValue());
        }

        Triple<ArrayList<MyTransactionOutPoint>, ArrayList<TransactionOutput>, ArrayList<UTXO>> ret = Triple.of(new ArrayList<MyTransactionOutPoint>(), new ArrayList<TransactionOutput>(), new ArrayList<UTXO>());
        ret.getLeft().addAll(selectedOutpoints);
        ret.getMiddle().addAll(txOutputs);
        if(outputs0 != null)    {
            ret.getMiddle().addAll(outputs0);
        }
        ret.getRight().addAll(_utxos);

        outValue += biFee.longValue();

        debug("SendFactory", "inputs:" + inValue);
        debug("SendFactory", "outputs:" + outValue);

        return ret;

    }

    private static String getChangeAddress(int type, int account)    {

        boolean useLikeType = PrefsUtil.getInstance(context).getValue(PrefsUtil.USE_LIKE_TYPED_CHANGE, true);
        if (DojoUtil.getInstance(context).getDojoParams() != null && !DojoUtil.getInstance(context).isLikeType()) {
            useLikeType = false;
        }

        if(type != 44 || !useLikeType)    {
            ;
        }
        else    {
            type = 44;
        }

        if(account == WhirlpoolMeta.getInstance(context).getWhirlpoolPostmix())    {
            // POSTMIX
            BipFormat forcedBipFormat = BIP_FORMAT.SEGWIT_NATIVE;
            if((type == 44 || type == 49) && useLikeType)    {
                if(type == 49)    {
                    forcedBipFormat = BIP_FORMAT.SEGWIT_COMPAT;
                }
                else {
                    forcedBipFormat = BIP_FORMAT.LEGACY;
                }
            }
            // get & increment
            Pair<Integer, String> postmixChange = AddressFactory.getInstance(context).getAddressAndIncrement(WALLET_INDEX.POSTMIX_CHANGE, forcedBipFormat);
            debug("SendFactory", "change index:" + postmixChange.getLeft());
            return postmixChange.getRight();
        }

        // DEPOSIT
        WALLET_INDEX walletIndex = WALLET_INDEX.findChangeIndex(0, type);

        // get & increment
        Pair<Integer, String> change = AddressFactory.getInstance(context).getAddressAndIncrement(walletIndex);
        debug("SendFactory", "change index:" + change.getLeft());
        return change.getRight();
    }

    public static ECKey getPrivKey(String address, int account)    {

//        debug("SendFactory", "get privkey for:" + address);

        ECKey ecKey = null;

        String path = APIFactory.getInstance(context).getUnspentPaths().get(address);
        try {
            debug("SendFactory", "address path:" + path);
            if(path != null) {
                String[] s = path.split("/");
                if(FormatsUtil.getInstance().isValidBech32(address))    {
                    debug("SendFactory", "address type:" + "bip84");
                    HD_Address addr = null;
                    addr = BIP84Util.getInstance(context).getWallet().getAccount(account).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
                    ecKey = addr.getECKey();
                }
                else if(Address.fromBase58(SamouraiWallet.getInstance().getCurrentNetworkParams(), address).isP2SHAddress())    {
                    if(account == WhirlpoolMeta.getInstance(context).getWhirlpoolPostmix())    {
                        debug("SendFactory", "address type:" + "post-mix p2sh");
                        HD_Address addr = BIP84Util.getInstance(context).getWallet().getAccount(WhirlpoolMeta.getInstance(context).getWhirlpoolPostmix()).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
                        ecKey = addr.getECKey();
                    } else {
                        debug("SendFactory", "address type:" + "bip49");
                        HD_Address addr =  BIP49Util.getInstance(context).getWallet().getAccount(0).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
                        ecKey = addr.getECKey();
                    }
                }
                else {
                    if(account == WhirlpoolMeta.getInstance(context).getWhirlpoolPostmix())    {
                        debug("SendFactory", "address type:" + "post-mix p2pkh");
                        HD_Address hd_addr = BIP84Util.getInstance(context).getWallet().getAccount(WhirlpoolMeta.getInstance(context).getWhirlpoolPostmix()).getChain(Integer.parseInt(s[1])).getAddressAt(Integer.parseInt(s[2]));
                        ecKey = hd_addr.getECKey();
                    } else {
                        debug("SendFactory", "address type:" + "bip44");
                        int account_no = APIFactory.getInstance(context).getUnspentAccounts().get(address);
                        HD_Address hd_address = AddressFactory.getInstance(context).get(account_no, Integer.parseInt(s[1]), Integer.parseInt(s[2]));
                        String strPrivKey = hd_address.getPrivateKeyString();
                        DumpedPrivateKey pk = new DumpedPrivateKey(SamouraiWallet.getInstance().getCurrentNetworkParams(), strPrivKey);
                        ecKey = pk.getKey();
                    }
                }
            } else {
                debug("SendFactory", "address type:" + "bip47");
                debug("SendFactory", "address:" + address);
                String pcode = BIP47Meta.getInstance().getPCode4Addr(address);
                debug("SendFactory", "pcode:" + pcode);
                int idx = BIP47Meta.getInstance().getIdx4Addr(address);
                ecKey = BIP47Util.getInstance(context).getReceiveAddress(new PaymentCode(pcode), idx).getECKey();
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }

        return ecKey;
    }

    public static class BIP69InputComparator extends com.samourai.wallet.bip69.BIP69InputComparator {

        public int compare(MyTransactionInput i1, MyTransactionInput i2) {

            byte[] h1 = Hex.decode(i1.getTxHash());
            byte[] h2 = Hex.decode(i2.getTxHash());

            int index1 = i1.getTxPos();
            int index2 = i2.getTxPos();

            return super.compare(h1, h2, index1, index2);
        }

    }

}
