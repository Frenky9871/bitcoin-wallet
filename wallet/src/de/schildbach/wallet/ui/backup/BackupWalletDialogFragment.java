/*
 * Copyright the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.backup;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.Html;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import com.google.common.io.CharStreams;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.R;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.AbstractWalletActivityViewModel;
import de.schildbach.wallet.ui.DialogBuilder;
import de.schildbach.wallet.ui.ShowPasswordCheckListener;
import de.schildbach.wallet.util.Crypto;
import de.schildbach.wallet.util.Toast;
import de.schildbach.wallet.util.WalletUtils;

import org.bitcoinj.crypto.KeyCrypter;
import org.bitcoinj.crypto.KeyCrypterScrypt;
import org.bitcoinj.wallet.Protos;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.WalletProtobufSerializer;
import org.bouncycastle.crypto.params.KeyParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.TimeZone;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.google.common.base.Preconditions.checkState;

/**
 * @author Andreas Schildbach
 */
public class BackupWalletDialogFragment extends DialogFragment {
    private static final String FRAGMENT_TAG = BackupWalletDialogFragment.class.getName();

    public static void show(final FragmentManager fm) {
        final DialogFragment newFragment = new BackupWalletDialogFragment();
        newFragment.show(fm, FRAGMENT_TAG);
    }

    private AbstractWalletActivity activity;
    private WalletApplication application;

    private EditText passwordView, passwordAgainView;
    private TextView passwordStrengthView;
    private View passwordMismatchView;
    private CheckBox showView;
    private TextView warningView;
    private View spendingPINViewGroup;
    private EditText spendingPINView;
    private TextView badSpendingPINView;
    private Button positiveButton, negativeButton;
    private boolean spendingPINSet =  false;
    private String pin;
    private Executor executor = Executors.newSingleThreadExecutor();
    private Wallet wallet;
    private AbstractWalletActivityViewModel walletActivityViewModel;
    private BackupWalletViewModel viewModel;
    private static final Logger log = LoggerFactory.getLogger(BackupWalletDialogFragment.class);

    private final ActivityResultLauncher<String> createDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument(Constants.MIMETYPE_WALLET_BACKUP),
                    uri -> {
                        if (uri != null) {
                            walletActivityViewModel.wallet.observe(this, new Observer<Wallet>() {
                                @Override
                                public void onChanged(final Wallet wallet) {
                                    walletActivityViewModel.wallet.removeObserver(this);

                                    final String targetProvider = WalletUtils.uriToProvider(uri);
                                    final String password = passwordView.getText().toString().trim();
                                    checkState(!password.isEmpty());
                                    wipePasswords();
                                    dismiss();

                                    byte[] plainBytes = null;
                                    try (final Writer cipherOut = new OutputStreamWriter(
                                            activity.getContentResolver().openOutputStream(uri),
                                            StandardCharsets.UTF_8)) {
                                        final Protos.Wallet walletProto =
                                                new WalletProtobufSerializer().walletToProto(wallet);
                                        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                                        walletProto.writeTo(baos);
                                        baos.close();
                                        plainBytes = baos.toByteArray();

                                        final String cipherText = Crypto.encrypt(plainBytes, password.toCharArray());
                                        cipherOut.write(cipherText);
                                        cipherOut.flush();

                                        log.info("backed up wallet to: '{}'{}, {} characters written", uri,
                                                targetProvider != null ? " (" + targetProvider + ")" : "",
                                                cipherText.length());
                                    } catch (final IOException x) {
                                        log.error("problem backing up wallet to " + uri, x);
                                        ErrorDialogFragment.showDialog(getParentFragmentManager(), x.toString());
                                        return;
                                    } finally {
                                        if (badEncryptionState()) {
                                            reEncryptWallet();
                                        }
                                    }

                                    try (final Reader cipherIn = new InputStreamReader(
                                            activity.getContentResolver().openInputStream(uri),
                                            StandardCharsets.UTF_8)) {
                                        final StringBuilder cipherText = new StringBuilder();
                                        CharStreams.copy(cipherIn, cipherText);
                                        cipherIn.close();

                                        final byte[] plainBytes2 = Crypto.decryptBytes(cipherText.toString(),
                                                password.toCharArray());
                                        if (!Arrays.equals(plainBytes, plainBytes2))
                                            throw new IOException("verification failed");

                                        log.info("verified successfully: '" + uri + "'");
                                        application.getConfiguration().disarmBackupReminder();
                                        SuccessDialogFragment.showDialog(getParentFragmentManager(),
                                                targetProvider != null ? targetProvider : uri.toString());
                                    } catch (final IOException x) {
                                        log.error("problem verifying backup from " + uri, x);
                                        ErrorDialogFragment.showDialog(getParentFragmentManager(), x.toString());
                                        return;
                                    }
                                }
                            });
                        } else {
                            log.info("cancelled backing up wallet");
                            passwordView.setEnabled(true);
                            passwordAgainView.setEnabled(true);
                            activity.finish();
                        }
                    });

    private final TextWatcher passwordTextWatcher = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            viewModel.password.postValue(s.toString().trim());
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    private final TextWatcher spendingPINTextWatcher = new TextWatcher() {
        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
            viewModel.spendingPIN.postValue(s.toString().trim());
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void afterTextChanged(final Editable s) {
        }
    };

    @Override
    public void onAttach(final Context context) {
        super.onAttach(context);
        this.activity = (AbstractWalletActivity) context;
        this.application = activity.getWalletApplication();
        this.wallet = application.getWallet();
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        log.info("opening dialog {}", getClass().getName());

        walletActivityViewModel = new ViewModelProvider(activity).get(AbstractWalletActivityViewModel.class);
        viewModel = new ViewModelProvider(this).get(BackupWalletViewModel.class);
    }

    @Override
    public Dialog onCreateDialog(final Bundle savedInstanceState) {
        final View view = LayoutInflater.from(activity).inflate(R.layout.backup_wallet_dialog, null);

        passwordView = view.findViewById(R.id.backup_wallet_dialog_password);
        passwordView.setText(null);

        passwordAgainView = view.findViewById(R.id.backup_wallet_dialog_password_again);
        passwordAgainView.setText(null);

        passwordStrengthView = view.findViewById(R.id.backup_wallet_dialog_password_strength);

        passwordMismatchView = view.findViewById(R.id.backup_wallet_dialog_password_mismatch);

        showView = view.findViewById(R.id.backup_wallet_dialog_show);

        spendingPINViewGroup = view.findViewById(R.id.backup_wallet_dialog_spending_pin_group);
        spendingPINView = view.findViewById(R.id.backup_wallet_dialog_spending_pin);
        badSpendingPINView = view.findViewById(R.id.backup_wallet_dialog_bad_spending_pin);

        warningView = view.findViewById(R.id.backup_wallet_dialog_warning_encrypted);

        if (wallet.isEncrypted()) {
            warningView.setVisibility(wallet.isEncrypted() ? View.VISIBLE : View.GONE);
            spendingPINViewGroup.setVisibility(wallet.isEncrypted() ? View.VISIBLE : View.GONE);
            spendingPINSet =wallet.isEncrypted();
        }

        final DialogBuilder builder = DialogBuilder.custom(activity, R.string.export_keys_dialog_title, view);
        // dummies, just to make buttons show
        builder.setPositiveButton(R.string.export_keys_dialog_button_export, null);
        builder.setNegativeButton(R.string.button_cancel, null);

        final AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnShowListener(d -> {
            positiveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
            positiveButton.setEnabled(false);
            positiveButton.setTypeface(Typeface.DEFAULT_BOLD);
            positiveButton.setOnClickListener(v -> handleGo());

            negativeButton = dialog.getButton(DialogInterface.BUTTON_NEGATIVE);
            negativeButton.setOnClickListener(v -> {
                dismissAllowingStateLoss();
                activity.finish();
            });

            passwordView.addTextChangedListener(passwordTextWatcher);
            passwordAgainView.addTextChangedListener(passwordTextWatcher);
            spendingPINView.addTextChangedListener(spendingPINTextWatcher);
            showView.setOnCheckedChangeListener(new ShowPasswordCheckListener(passwordView, passwordAgainView));

            viewModel.spendingPIN.observe(BackupWalletDialogFragment.this, spendingPIN -> {
                badSpendingPINView.setVisibility(View.INVISIBLE);
                if (positiveButton != null) {
                    positiveButton.setEnabled(preConditionsSatisfied());
                }

            });

            viewModel.state.observe(BackupWalletDialogFragment.this, state -> {
                updateView();
            });

            viewModel.password.observe(BackupWalletDialogFragment.this, password -> {
                passwordMismatchView.setVisibility(View.INVISIBLE);

                final int passwordLength = password.length();
                passwordStrengthView.setVisibility(passwordLength > 0 ? View.VISIBLE : View.INVISIBLE);
                if (passwordLength < 6) {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_weak);
                    passwordStrengthView
                            .setTextColor(activity.getColor(R.color.fg_password_strength_weak));
                } else if (passwordLength < 8) {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_fair);
                    passwordStrengthView
                            .setTextColor(activity.getColor(R.color.fg_password_strength_fair));
                } else if (passwordLength < 10) {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_good);
                    passwordStrengthView
                            .setTextColor(activity.getColor(R.color.fg_password_strength_good));
                } else {
                    passwordStrengthView.setText(R.string.encrypt_keys_dialog_password_strength_strong);
                    passwordStrengthView.setTextColor(
                            activity.getColor(R.color.fg_password_strength_strong));
                }

                if (positiveButton != null) {
                    positiveButton.setEnabled(preConditionsSatisfied());
                }
            });
        });

        return dialog;
    }

    @Override
    public void onDismiss(final DialogInterface dialog) {
        passwordView.removeTextChangedListener(passwordTextWatcher);
        passwordAgainView.removeTextChangedListener(passwordTextWatcher);
        spendingPINView.removeTextChangedListener(spendingPINTextWatcher);

        showView.setOnCheckedChangeListener(null);

        wipePasswords();

        super.onDismiss(dialog);
    }

    @Override
    public void onCancel(final DialogInterface dialog) {
        // re-encrypt wallet if it was previously decrypted
        if (badEncryptionState()) {
            reEncryptWallet();
        }

        activity.finish();
        super.onCancel(dialog);
    }

    private void handleGo() {
        final String password = passwordView.getText().toString().trim();
        final String passwordAgain = passwordAgainView.getText().toString().trim();

        if (passwordAgain.equals(password)) {

            if (wallet.isEncrypted()) {
                setState(BackupWalletViewModel.State.CRYPTING);

                executor.execute(() -> {
                    final String inputPIN = spendingPINView.getText().toString().trim();

                    try {
                        final KeyCrypter keyCrypter = wallet.getKeyCrypter();
                        final KeyParameter derivedAesKey = keyCrypter.deriveKey(inputPIN);
                        wallet.decrypt(derivedAesKey);
                        pin = inputPIN;

                        log.info("wallet successfully decrypted for back up");
                        setState(BackupWalletViewModel.State.EXPORTING);
                        backupWallet();

                    } catch (final Wallet.BadWalletEncryptionKeyException x) {
                        log.info("wallet decryption failed, bad spending password: " + x.getMessage());
                        setState(BackupWalletViewModel.State.BADPIN);
                    }
                });
            } else {
                setState(BackupWalletViewModel.State.EXPORTING);
                backupWallet();
                setState(BackupWalletViewModel.State.INPUT);
            }
        } else {
            passwordMismatchView.setVisibility(View.VISIBLE);
            setState(BackupWalletViewModel.State.INPUT);
        }
    }

    private void setState(final BackupWalletViewModel.State state) {
        viewModel.state.postValue(state);
    }

    private void updateView() {

        BackupWalletViewModel.State currentState = viewModel.state.getValue();

        if (currentState == BackupWalletViewModel.State.INPUT || currentState ==BackupWalletViewModel.State.BADPIN) {
            showView.setEnabled(true);
            negativeButton.setEnabled(true); // prevent the user from cancelling in a decrypted wallet state
            positiveButton.setEnabled(preConditionsSatisfied());
            positiveButton.setText(R.string.export_keys_dialog_button_export);
            spendingPINView.setEnabled(true);
            passwordView.setEnabled(true);
            passwordAgainView.setEnabled(true);
            if (currentState ==BackupWalletViewModel.State.BADPIN) {
                badSpendingPINView.setVisibility(View.VISIBLE);
            }
        } else if (currentState == BackupWalletViewModel.State.CRYPTING) {
            negativeButton.setEnabled(false); // prevent the user from cancelling in a decrypted wallet state
            showView.setEnabled(false);
            positiveButton.setEnabled(false);
            positiveButton.setText(R.string.backup_wallet_dialog_state_verifying);
            spendingPINView.setEnabled(false);
            passwordView.setEnabled(false);
            passwordAgainView.setEnabled(false);
        } else if (currentState == BackupWalletViewModel.State.EXPORTING) {
            negativeButton.setEnabled(true);
            positiveButton.setEnabled(false);
            positiveButton.setText(R.string.backup_wallet_dialog_state_verifying);
            spendingPINView.setEnabled(false);
            passwordView.setEnabled(false);
            passwordAgainView.setEnabled(false);
        }
    }

    private boolean isSpendingPINPlausible() {
        if (wallet == null)
            return false;
        if (!wallet.isEncrypted())
            return true;
        return !spendingPINView.getText().toString().trim().isEmpty();
    }
    private void reEncryptWallet() {
        executor.execute(() -> {
            checkState(!wallet.isEncrypted());

            try {
                checkState(!pin.isEmpty());
                final KeyCrypterScrypt keyCrypter = new KeyCrypterScrypt(application.scryptIterationsTarget());
                final KeyParameter aesKey = keyCrypter.deriveKey(pin);

                wallet.encrypt(keyCrypter , aesKey);
                log.info("wallet successfully decrypted");
            } catch (final Wallet.BadWalletEncryptionKeyException x) {
                log.error("wallet decryption failed, bad spending password");
            }

        });
    }

    private boolean badEncryptionState() {
        return !spendingPINSet ? false : !wallet.isEncrypted();
    }

    private boolean preConditionsSatisfied() {
        final boolean hasPassword = !passwordView.getText().toString().trim().isEmpty();
        final boolean hasPasswordAgain = !passwordAgainView.getText().toString().trim().isEmpty();
        return wallet != null && hasPassword && hasPasswordAgain && isSpendingPINPlausible();
    }

    private void wipePasswords() {
        passwordView.setText(null);
        passwordAgainView.setText(null);
        spendingPINView.setText(null);
    }

    private void backupWallet() {
        passwordView.setEnabled(false);
        passwordAgainView.setEnabled(false);

        final DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm");
        final StringBuilder filename = new StringBuilder(Constants.Files.EXTERNAL_WALLET_BACKUP);
        filename.append('-');
        filename.append(dateFormat.format(Instant.now().atZone(TimeZone.getDefault().toZoneId())));

        try {
            createDocumentLauncher.launch(filename.toString());
        } catch (final ActivityNotFoundException x) {
            log.warn("Cannot open document selector: {}", filename);
            new Toast(activity).longToast(R.string.toast_start_storage_provider_selector_failed);
        }
    }

    public static class SuccessDialogFragment extends DialogFragment {
        private static final String FRAGMENT_TAG = SuccessDialogFragment.class.getName();
        private static final String KEY_TARGET = "target";

        private Activity activity;

        public static void showDialog(final FragmentManager fm, final String target) {
            final DialogFragment newFragment = new SuccessDialogFragment();
            final Bundle args = new Bundle();
            args.putString(KEY_TARGET, target);
            newFragment.setArguments(args);
            newFragment.show(fm, FRAGMENT_TAG);
        }

        @Override
        public void onAttach(final Context context) {
            super.onAttach(context);
            this.activity = (Activity) context;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final String target = getArguments().getString(KEY_TARGET);
            final DialogBuilder dialog = DialogBuilder.dialog(activity, R.string.export_keys_dialog_title,
                    Html.fromHtml(getString(R.string.export_keys_dialog_success, target)));
            dialog.singleDismissButton((d, id) -> activity.finish());
            return dialog.create();
        }
    }

    public static class ErrorDialogFragment extends DialogFragment {
        private static final String FRAGMENT_TAG = ErrorDialogFragment.class.getName();
        private static final String KEY_EXCEPTION_MESSAGE = "exception_message";

        private Activity activity;

        public static void showDialog(final FragmentManager fm, final String exceptionMessage) {
            final DialogFragment newFragment = new ErrorDialogFragment();
            final Bundle args = new Bundle();
            args.putString(KEY_EXCEPTION_MESSAGE, exceptionMessage);
            newFragment.setArguments(args);
            newFragment.show(fm, FRAGMENT_TAG);
        }

        @Override
        public void onAttach(final Context context) {
            super.onAttach(context);
            this.activity = (Activity) context;
        }

        @Override
        public Dialog onCreateDialog(final Bundle savedInstanceState) {
            final String exceptionMessage = getArguments().getString(KEY_EXCEPTION_MESSAGE);
            final DialogBuilder dialog = DialogBuilder.warn(activity,
                    R.string.import_export_keys_dialog_failure_title, R.string.export_keys_dialog_failure,
                    exceptionMessage);
            dialog.singleDismissButton((d, id) -> activity.finish());
            return dialog.create();
        }
    }
}
