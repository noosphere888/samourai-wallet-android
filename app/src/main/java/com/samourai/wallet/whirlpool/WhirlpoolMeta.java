package com.samourai.wallet.whirlpool;

import android.content.Context;

import com.samourai.wallet.constants.SamouraiAccountIndex;

import org.json.JSONException;
import org.json.JSONObject;

public class WhirlpoolMeta {

    public final static double WHIRLPOOL_FEE_RATE_POOL_DENOMINATION = 0.05;

    private static String strSCODE = null;

    private static WhirlpoolMeta instance = null;

    private static Context context = null;

    private WhirlpoolMeta() { ; }

    public static WhirlpoolMeta getInstance(Context ctx) {

        context = ctx;

        if(instance == null) {
            instance = new WhirlpoolMeta();
        }

        return instance;
    }

    public int getWhirlpoolPremixAccount() {
        return SamouraiAccountIndex.PREMIX;
    }

    public int getWhirlpoolPostmix() {
        return SamouraiAccountIndex.POSTMIX;
    }

    public int getWhirlpoolBadBank() {
        return SamouraiAccountIndex.BADBANK;
    }

    public static long getMinimumPoolDenomination() {
        return 100000L;
    }

    public void setSCODE(String scode) {

        if(scode == null) {
            strSCODE = null;
        }
        else {
            strSCODE = scode.toUpperCase();
        }

    }

    public String getSCODE() {
        return (strSCODE != null && strSCODE.length() > 0) ? strSCODE : null;
    }

    public JSONObject toJSON() {

        JSONObject scode = new JSONObject();

        try {
            if(strSCODE != null && strSCODE.length() > 0) {
                scode.put("scode", strSCODE);
            }
        }
        catch(JSONException ex) {
            throw new RuntimeException(ex);
        }

        return scode;
    }

    public void fromJSON(JSONObject obj) {

        try {
            if(obj.has("scode") && obj.getString("scode").length() > 0) {
                strSCODE = obj.getString("scode");
            }
        }
        catch(JSONException ex) {
            throw new RuntimeException(ex);
        }

    }

}
