package com.reactnativechangeicon;

import androidx.annotation.NonNull;

import android.app.Activity;
import android.app.Application;
import android.content.pm.PackageManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.content.Intent;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;

import java.util.ArrayList;
import java.util.List;

@ReactModule(name = "ChangeIcon")
public class ChangeIconModule extends ReactContextBaseJavaModule implements Application.ActivityLifecycleCallbacks {
    public static final String NAME = "ChangeIcon";

    private final String packageName;
    private final List<String> classesToKill = new ArrayList<>();

    private boolean iconChanged = false;
    private String componentClass = "";
    private final ReactApplicationContext reactContext;

    // Delay curto para evitar crash com RNVideo/ExoPlayer enquanto batches ainda estão sendo aplicados
    private static final long DISABLE_OLD_ALIASES_DELAY_MS = 600;

    public ChangeIconModule(ReactApplicationContext reactContext, String packageName) {
        super(reactContext);
        this.packageName = packageName;
        this.reactContext = reactContext;
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    @ReactMethod
    public void getIcon(Promise promise) {
        final Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("ANDROID:ACTIVITY_NOT_FOUND");
            return;
        }

        final String activityName = activity.getComponentName().getClassName();

        if (activityName.endsWith("MainActivity")) {
            promise.resolve("Default");
            return;
        }

        String[] activityNameSplit = activityName.split("MainActivity");
        if (activityNameSplit.length != 2) {
            promise.reject("ANDROID:UNEXPECTED_COMPONENT_CLASS:" + this.componentClass);
            return;
        }

        promise.resolve(activityNameSplit[1]);
    }

    @ReactMethod
    public void changeIcon(String iconName, Promise promise) {
        final Activity activity = getCurrentActivity();
        if (activity == null) {
            promise.reject("ANDROID:ACTIVITY_NOT_FOUND");
            return;
        }

        final String activityName = activity.getComponentName().getClassName();

        // Mantém o padrão original da lib:
        // - Se estiver em MainActivity "real", assume que o componente atual é MainActivityDefault (alias)
        // - Caso contrário, usa o nome real do componente atual (alias)
        if (this.componentClass.isEmpty()) {
            this.componentClass = activityName.endsWith("MainActivity") ? activityName + "Default" : activityName;
        }

        final String newIconName = (iconName == null || iconName.isEmpty()) ? "Default" : iconName;
        final String activeClass = this.packageName + ".MainActivity" + newIconName;

        if (this.componentClass.equals(activeClass)) {
            promise.reject("ANDROID:ICON_ALREADY_USED:" + this.componentClass);
            return;
        }

        try {
            // 1) Habilita o NOVO alias (NUNCA desabilite o atual antes disso)
            activity.getPackageManager().setComponentEnabledSetting(
                new ComponentName(this.packageName, activeClass),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            );

            promise.resolve(newIconName);

        } catch (Exception e) {
            promise.reject("ANDROID:ICON_INVALID");
            return;
        }

        // 2) Marca o alias antigo para desabilitar depois (somente quando a nova Activity já estiver ativa)
        this.classesToKill.add(this.componentClass);
        this.componentClass = activeClass;

        // 3) Registra lifecycle para concluir a troca com segurança
        try {
            activity.getApplication().registerActivityLifecycleCallbacks(this);
        } catch (Exception ignored) {}

        iconChanged = true;

        // 4) Finaliza a Activity atual e abre a nova "Main" via alias ativo
        activity.finish();

        Intent newIconIntent = Intent.makeMainActivity(new ComponentName(this.packageName, activeClass));
        newIconIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        this.reactContext.startActivity(newIconIntent);
    }

    /**
     * Desabilita aliases antigos APÓS a nova Activity estar "de pé".
     * Recebe a Activity diretamente do callback para evitar getCurrentActivity() nulo ou incorreto.
     */
    private void completeIconChange(Activity activity) {
        if (!iconChanged) return;
        if (activity == null) return;

        try {
            for (String cls : classesToKill) {
                activity.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this.packageName, cls),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                );
            }
        } catch (Exception ignored) {
            // Se falhar, não derruba o app. No próximo start você pode tentar limpar novamente.
        } finally {
            classesToKill.clear();
            iconChanged = false;

            // Evita ficar com callbacks registrados para sempre
            try {
                activity.getApplication().unregisterActivityLifecycleCallbacks(this);
            } catch (Exception ignored2) {}
        }
    }

    /**
     * IMPORTANTE:
     * Não desabilitar no onActivityPaused, pois RN/ExoPlayer ainda podem consultar ActivityInfo do alias atual
     * durante desmontagem/relayout e isso causa NameNotFoundException / crash.
     */
    @Override
    public void onActivityPaused(Activity activity) {
        // NÃO FAÇA NADA AQUI
    }

    /**
     * Desabilita os aliases antigos quando a nova Activity estiver resumed.
     * Atraso curto para garantir que batches de UI do RN (ex.: RCTVideo) já estabilizaram.
     */
    @Override
    public void onActivityResumed(Activity activity) {
        if (!iconChanged) return;
        if (activity == null) return;

        try {
            activity.getWindow().getDecorView().postDelayed(() -> completeIconChange(activity), DISABLE_OLD_ALIASES_DELAY_MS);
        } catch (Exception e) {
            // fallback sem delay
            completeIconChange(activity);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}

    @Override
    public void onActivityStarted(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}
}
