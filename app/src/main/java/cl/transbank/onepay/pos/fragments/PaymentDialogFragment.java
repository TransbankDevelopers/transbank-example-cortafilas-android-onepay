package cl.transbank.onepay.pos.fragments;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v4.app.DialogFragment;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;

import cl.ionix.tbk_ewallet_sdk_android.ui.QROnepayView;
import cl.transbank.onepay.pos.R;
import cl.transbank.onepay.pos.model.Item;
import cl.transbank.onepay.pos.utils.HTTPClient;
import cl.transbank.onepay.pos.utils.KeyValuePersistence;
import cl.transbank.onepay.pos.utils.StringFormatter;

public class PaymentDialogFragment extends DialogFragment {
    private static final String ARG_ITEMS = "items";

    private ArrayList<Item> mItems;

    private OnFragmentInteractionListener mListener;

    BroadcastReceiver br;

    private String mOcc;
    private String mExternalUniqueNumber;
    private String mOtt;
    private Integer totalTimeMillisecsLeft;
    private Animator smoothAnimation;

    private String mPaymentDescription;

    ProgressBar waitingProgressBar;

    private boolean isPaymentProcessed = false;

    public PaymentDialogFragment() {

    }

    public static PaymentDialogFragment newInstance(ArrayList<Item> items) {
        PaymentDialogFragment fragment = new PaymentDialogFragment();
        Bundle args = new Bundle();
        args.putParcelableArrayList(ARG_ITEMS, items);

        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mItems = getArguments().getParcelableArrayList(ARG_ITEMS);
        }

        setStyle(DialogFragment.STYLE_NORMAL, android.R.style.Theme_Material_Light_Dialog);
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        if (outState != null) {
            totalTimeMillisecsLeft = waitingProgressBar.getProgress();

            outState.putString("mOcc", mOcc);
            outState.putString("mOtt", mOtt);
            outState.putString("mExternalUniqueNumber", mExternalUniqueNumber);
            outState.putInt("totalTimeMillisecsLeft", totalTimeMillisecsLeft);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        getDialog().requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCancelable(false);

        final View inflatedView = inflater.inflate(R.layout.fragment_payment_dialog, container, false);
        Button cancelButton = inflatedView.findViewById(R.id.cancelButton);
        cancelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                getDialog().dismiss();
            }
        });

        if (savedInstanceState != null) {
            mOcc = savedInstanceState.getString("mOcc");
            mExternalUniqueNumber = savedInstanceState.getString("mExternalUniqueNumber");
            mOtt = savedInstanceState.getString("mOtt");
            totalTimeMillisecsLeft = savedInstanceState.getInt("totalTimeMillisecsLeft");

            showQR(mOtt, inflatedView);
        } else {
            HTTPClient.createTransaction(mItems, getContext(), new HTTPClient.HTTPClientListener() {
                @Override
                public void onCompleted(JsonObject result) {
                    if (result == null) {
                        dismiss();
                        return;
                    }

                    mOcc = result.getAsJsonPrimitive("occ").getAsString();
                    mExternalUniqueNumber = result.getAsJsonPrimitive("externalUniqueNumber").getAsString();
                    mOtt = result.getAsJsonPrimitive("ott").getAsString();

                    showQR(mOtt, inflatedView);
                }
            });
        }

        return inflatedView;
    }

    private void initializeAlertDialogUI(View inflatedView) {
        waitingProgressBar = inflatedView.findViewById(R.id.waiting_progress_bar);

        final int totalTimeMillisecs = 90000;

        if (totalTimeMillisecsLeft == null) {
            System.out.println(totalTimeMillisecsLeft);
            totalTimeMillisecsLeft = totalTimeMillisecs;
        }

        int mTimeLeftMills = totalTimeMillisecsLeft;
        waitingProgressBar.setMax(totalTimeMillisecs);
        waitingProgressBar.setProgress(mTimeLeftMills);

        smoothAnimation = ObjectAnimator.ofInt(waitingProgressBar, "progress", mTimeLeftMills, 0);

        smoothAnimation.setDuration(mTimeLeftMills);
        smoothAnimation.setInterpolator(new LinearInterpolator());

        smoothAnimation.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!isPaymentProcessed) {
                    dismiss();

                    new AlertDialog.Builder(getActivity())
                            .setTitle(R.string.error_title)
                            .setMessage(R.string.time_limit_passed)
                            .setNegativeButton("Okey", null)
                            .show();
                }
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });

        smoothAnimation.start();
    }

    public void onPaymentDone(String result, String externalUniqueNumber) {
        if (mListener != null) {
            mListener.onPaymentDone(result, externalUniqueNumber);
        }
    }

    @Override
    public void onAttach(Context context) {
        super.onAttach(context);
        if (context instanceof OnFragmentInteractionListener) {
            mListener = (OnFragmentInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString()
                    + " must implement OnFragmentInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }

    public interface OnFragmentInteractionListener {
        void onPaymentDone(String result, String externalUniqueNumber);
    }

    public void parseResult(HashMap data) {
        isPaymentProcessed = true;
        if (data.get("occ").equals(mOcc)) {
            mPaymentDescription = (String) data.get("description");

            if (mPaymentDescription != null && mPaymentDescription.equals("OK")) {
                showPaymentInfo();
            } else {
                showError();
            }
        }

        Log.d("POS", data.toString());
        KeyValuePersistence.clearLastPayment(getContext());
    }

    @Override
    public void onResume() {
        initializeAlertDialogUI(getView());
        HashMap paymentHashMap = KeyValuePersistence.getLastPayment(getContext());
        
        if (paymentHashMap != null) {
            parseResult(paymentHashMap);
        } else {
            br = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    Log.d("POS", "transaction_result");
                    HashMap data = (HashMap) intent.getSerializableExtra("data");
                    parseResult(data);
                }
            };
            LocalBroadcastManager.getInstance(getActivity()).registerReceiver(br, new IntentFilter(("transaction_result")));
        }

        super.onResume();
    }

    @Override
    public void onStop() {
        super.onStop();
        if (smoothAnimation != null)
            smoothAnimation.pause();
        LocalBroadcastManager.getInstance(getActivity()).unregisterReceiver(br);
    }

    private void showError() {
        if (smoothAnimation != null)
            smoothAnimation.cancel();

        View currentView = getView();

        Button cancelButton = currentView.findViewById(R.id.cancelButton);
        cancelButton.setText(R.string.close_and_continue);

        View mPaidView = currentView.findViewById(R.id.error_constraintLayout);
        final View mPaymentView = currentView.findViewById(R.id.payment_constraintLayout);

        mPaidView.setAlpha(0f);
        mPaidView.setVisibility(View.VISIBLE);

        int mShortAnimationDuration = getResources().getInteger(
                android.R.integer.config_longAnimTime);

        mPaidView.animate()
                .alpha(1f)
                .setDuration(mShortAnimationDuration)
                .setListener(null);

        mPaymentView.animate()
                .alpha(0f)
                .setDuration(mShortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPaymentView.setVisibility(View.INVISIBLE);
                    }
                });
    }

    private void showPaymentInfo() {
        if (smoothAnimation != null)
            smoothAnimation.cancel();

        View currentView = getView();

        Button cancelButton = currentView.findViewById(R.id.cancelButton);
        cancelButton.setText(R.string.close_and_continue);

        View mPaidView = currentView.findViewById(R.id.paid_constraintLayout);
        final View mPaymentView = currentView.findViewById(R.id.payment_constraintLayout);

        TextView mExternalUniqueNumberTextView = currentView.findViewById(R.id.external_unique_number_textview);
        mExternalUniqueNumberTextView.setText(getString(R.string.buy_order) + " " + mExternalUniqueNumber);
        mPaidView.setAlpha(0f);
        mPaidView.setVisibility(View.VISIBLE);

        int mShortAnimationDuration = getResources().getInteger(
                android.R.integer.config_longAnimTime);

        mPaidView.animate()
                .alpha(1f)
                .setDuration(mShortAnimationDuration)
                .setListener(null);

        mPaymentView.animate()
                .alpha(0f)
                .setDuration(mShortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mPaymentView.setVisibility(View.INVISIBLE);
                    }
                });
    }

    private void showQR(String ott, View inflatedView){
        try {
            QROnepayView imageViewQrCode = inflatedView.findViewById(R.id.qr_imageView);
            imageViewQrCode.setOtt(ott);
        } catch(Exception e) {

        }

        int mShortAnimationDuration = getResources().getInteger(
                android.R.integer.config_longAnimTime);

        TextView ottTextView = inflatedView.findViewById(R.id.buycode_textView);

        ottTextView.setText(StringFormatter.formatOtt(ott));
        View mContentView = inflatedView.findViewById(R.id.payment_constraintLayout);
        final View mLoadingView = inflatedView.findViewById(R.id.loading_constraintLayout);

        mContentView.setAlpha(0f);
        mContentView.setVisibility(View.VISIBLE);

        mContentView.animate()
                .alpha(1f)
                .setDuration(mShortAnimationDuration)
                .setListener(null);

        mLoadingView.animate()
                .alpha(0f)
                .setDuration(mShortAnimationDuration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        mLoadingView.setVisibility(View.GONE);
                    }
                });
    }

    @Override
    public void onDismiss(DialogInterface dialog) {
        super.onDismiss(dialog);
        onPaymentDone(mPaymentDescription, mExternalUniqueNumber);
    }
}
