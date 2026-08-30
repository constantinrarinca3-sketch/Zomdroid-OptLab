package com.zomdroid.fragments;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.OnBackPressedCallback;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.tabs.TabLayout;
import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.OptLabCustomPresetStore;
import com.zomdroid.OptLabFeatureRegistry;
import com.zomdroid.OptLabPreferences;
import com.zomdroid.R;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/** Full-screen, theme-aware OPT-LAB surface generated from the feature registry. */
public final class OptLabFragment extends Fragment {
    private static final int TAB_OVERVIEW = 0;
    private static final int TAB_RUNTIME = 1;
    private static final int TAB_DISPLAY = 2;
    private static final int TAB_BUILD42 = 3;
    private static final int TAB_NATIVE = 4;
    private static final int TAB_EXPERIMENTAL = 5;

    private static final OptLabPreferences.LabProfile[] LAB_PROFILES = {
            OptLabPreferences.LabProfile.SAFE,
            OptLabPreferences.LabProfile.RECOMMENDED,
            OptLabPreferences.LabProfile.AGGRESSIVE,
            OptLabPreferences.LabProfile.CUSTOM
    };
    private static final OptLabFeatureRegistry.Module[] BUILD42_MODULES = {
            OptLabFeatureRegistry.Module.MAIN_LOOP_PACING,
            OptLabFeatureRegistry.Module.WORLD_STREAM_CHUNK,
            OptLabFeatureRegistry.Module.FBO_RENDER_CELL,
            OptLabFeatureRegistry.Module.RENDER_HOTPATH,
            OptLabFeatureRegistry.Module.MODEL_RINGBUFFER,
            OptLabFeatureRegistry.Module.SHADER_UNIFORMS,
            OptLabFeatureRegistry.Module.MEMORY_ALLOCATION
    };

    private OptLabPreferences prefs;
    private NativeModulesPreferences nativePrefs;
    private TabLayout tabs;
    private FrameLayout pageHost;
    private View[] pages;
    private boolean updating;
    private boolean uiReady;
    // Spinner callbacks can be delivered after a programmatic setSelection().  Profiles rewrite
    // whole modules, so only a real user gesture is allowed to apply one.
    private boolean generalProfileGesture;
    private boolean build42ProfileGesture;
    private boolean box64Gesture;
    private boolean internalTabSelection;
    private Route currentRoute;
    private final Deque<Route> routeHistory = new ArrayDeque<>();

    private OptLabUi.SpinnerCard<OptLabPreferences.LabProfile> generalProfileCard;
    private OptLabUi.NavCard overviewRuntime;
    private OptLabUi.NavCard overviewDisplay;
    private OptLabUi.NavCard overviewBuild42;
    private OptLabUi.NavCard overviewNative;
    private OptLabUi.NavCard overviewExperimental;
    private OptLabUi.ToggleCard safeMode;

    private OptLabUi.ToggleCard generalMaster;
    private OptLabUi.ToggleCard quiet;
    private OptLabUi.ToggleCard buffered;
    private OptLabUi.ToggleCard sqlite;
    private OptLabUi.SpinnerCard<OptLabPreferences.Box64Policy> box64;
    private OptLabUi.ToggleCard surface;
    private OptLabUi.ToggleCard refresh;
    private OptLabUi.ToggleCard inputQueue;
    private OptLabUi.ToggleCard analog;
    private OptLabUi.ToggleCard coalesce;
    private OptLabUi.ToggleCard mobileGlLog;

    private FrameLayout build42Host;
    private View build42Home;
    private OptLabUi.SpinnerCard<OptLabPreferences.LabProfile> build42ProfileCard;
    private TextView build42ProfileStatus;
    private OptLabUi.ToggleCard onlyBuild42;
    private OptLabUi.ToggleCard advancedControls;
    private OptLabUi.NavCard customPresetCard;
    private View customPresetPage;
    private OptLabUi.NavCard customPresetStatusCard;
    private OptLabUi.SpinnerCard<OptLabCustomPresetStore.Preset> customPresetSpinner;
    private OptLabUi.TextFieldCard customPresetName;
    private MaterialButton customPresetCreate;
    private MaterialButton customPresetApply;
    private MaterialButton customPresetUpdate;
    private MaterialButton customPresetDelete;
    private MaterialButton customPresetReset;
    private String presetUiSelectedId;
    private String pendingPresetDeleteId;
    private boolean pendingPresetReset;
    private String presetUiMessage = "";
    private final EnumMap<OptLabFeatureRegistry.Module, View> modulePages =
            new EnumMap<>(OptLabFeatureRegistry.Module.class);
    private final EnumMap<OptLabFeatureRegistry.Module, OptLabUi.NavCard> moduleCards =
            new EnumMap<>(OptLabFeatureRegistry.Module.class);
    private final EnumMap<OptLabFeatureRegistry.Module, OptLabUi.NavCard> moduleDetailCards =
            new EnumMap<>(OptLabFeatureRegistry.Module.class);
    private final EnumMap<OptLabFeatureRegistry.Module, View> advancedNotes =
            new EnumMap<>(OptLabFeatureRegistry.Module.class);
    private final EnumMap<OptLabFeatureRegistry.Feature, OptLabUi.ToggleCard> featureCards =
            new EnumMap<>(OptLabFeatureRegistry.Feature.class);
    private final List<View> advancedOnlyViews = new ArrayList<>();
    private final List<MaterialButton> build42Buttons = new ArrayList<>();

    private OptLabUi.ToggleCard clipper;
    private FrameLayout experimentalHost;
    private View experimentalHome;
    private OptLabUi.NavCard experimentalNativeCard;
    private final EnumMap<OptLabFeatureRegistry.Module, View> experimentalPages =
            new EnumMap<>(OptLabFeatureRegistry.Module.class);
    private final EnumMap<OptLabFeatureRegistry.Module, OptLabUi.NavCard> experimentalModuleCards =
            new EnumMap<>(OptLabFeatureRegistry.Module.class);
    private OptLabUi.ToggleCard lighting;
    private OptLabUi.ToggleCard pathfinding;
    private OptLabUi.ToggleCard popMan;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_opt_lab, container, false);
        prefs = OptLabPreferences.from(requireContext());
        nativePrefs = NativeModulesPreferences.from(requireContext());
        tabs = root.findViewById(R.id.opt_lab_tabs);
        pageHost = root.findViewById(R.id.opt_lab_page_host);

        addTab(R.string.opt_lab_tab_overview);
        addTab(R.string.opt_lab_tab_runtime);
        addTab(R.string.opt_lab_tab_display);
        addTab(R.string.opt_lab_tab_build42);
        addTab(R.string.opt_lab_tab_native);
        addTab(R.string.opt_lab_tab_experimental);
        pages = new View[]{buildOverviewPage(), buildRuntimePage(), buildDisplayPage(),
                buildBuild42Page(), buildNativePage(), buildExperimentalPage()};
        for (int index = 0; index < pages.length; index++) {
            pages[index].setVisibility(index == TAB_OVERVIEW ? View.VISIBLE : View.GONE);
            pageHost.addView(pages[index], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT));
        }
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (internalTabSelection) {
                    internalTabSelection = false;
                    return;
                }
                navigateTopLevel(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {
                navigateTopLevel(tab.getPosition());
            }
        });
        bindListeners();
        uiReady = true;
        syncAll();
        currentRoute = Route.root(TAB_OVERVIEW);
        return root;
    }

    @Override public void onViewCreated(@NonNull View view,
                                        @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override public void handleOnBackPressed() {
                        if (navigateBack()) return;
                        setEnabled(false);
                        requireActivity().getOnBackPressedDispatcher().onBackPressed();
                    }
                });
    }

    @Override public void onResume() {
        super.onResume();
        if (uiReady) syncAll();
    }

    @Override public void onDestroyView() {
        uiReady = false;
        updating = false;
        pages = null;
        tabs = null;
        pageHost = null;
        generalProfileCard = null;
        overviewRuntime = null;
        overviewDisplay = null;
        overviewBuild42 = null;
        overviewNative = null;
        overviewExperimental = null;
        safeMode = null;
        generalMaster = null;
        quiet = null;
        buffered = null;
        sqlite = null;
        box64 = null;
        surface = null;
        refresh = null;
        inputQueue = null;
        analog = null;
        coalesce = null;
        mobileGlLog = null;
        build42Host = null;
        build42Home = null;
        build42ProfileCard = null;
        build42ProfileStatus = null;
        onlyBuild42 = null;
        advancedControls = null;
        customPresetCard = null;
        customPresetPage = null;
        customPresetStatusCard = null;
        customPresetSpinner = null;
        customPresetName = null;
        customPresetCreate = null;
        customPresetApply = null;
        customPresetUpdate = null;
        customPresetDelete = null;
        customPresetReset = null;
        presetUiSelectedId = null;
        pendingPresetDeleteId = null;
        pendingPresetReset = false;
        presetUiMessage = "";
        generalProfileGesture = false;
        build42ProfileGesture = false;
        box64Gesture = false;
        internalTabSelection = false;
        currentRoute = null;
        routeHistory.clear();
        clipper = null;
        experimentalHost = null;
        experimentalHome = null;
        experimentalNativeCard = null;
        experimentalPages.clear();
        experimentalModuleCards.clear();
        lighting = null;
        pathfinding = null;
        popMan = null;
        prefs = null;
        nativePrefs = null;
        modulePages.clear();
        moduleCards.clear();
        moduleDetailCards.clear();
        advancedNotes.clear();
        featureCards.clear();
        advancedOnlyViews.clear();
        build42Buttons.clear();
        super.onDestroyView();
    }

    private View buildOverviewPage() {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        generalProfileCard = OptLabUi.spinnerCard(requireContext(), R.drawable.optlab_ic_profile,
                getString(R.string.opt_lab_profile_general), LAB_PROFILES);
        OptLabUi.addCard(page.content, generalProfileCard.root);
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_profile_scope)));
        overviewRuntime = overviewCard(R.drawable.optlab_ic_runtime,
                R.string.opt_lab_runtime_card, TAB_RUNTIME);
        overviewDisplay = overviewCard(R.drawable.optlab_ic_display,
                R.string.opt_lab_display_card, TAB_DISPLAY);
        overviewBuild42 = overviewCard(R.drawable.optlab_ic_build42,
                R.string.opt_lab_build42_card, TAB_BUILD42);
        overviewNative = overviewCard(R.drawable.optlab_ic_native,
                R.string.opt_lab_native_card, TAB_NATIVE);
        overviewExperimental = overviewCard(R.drawable.optlab_ic_experimental,
                R.string.opt_lab_experimental_card, TAB_EXPERIMENTAL);
        OptLabUi.addCard(page.content, overviewRuntime.root);
        OptLabUi.addCard(page.content, overviewDisplay.root);
        OptLabUi.addCard(page.content, overviewBuild42.root);
        OptLabUi.addCard(page.content, overviewNative.root);
        OptLabUi.addCard(page.content, overviewExperimental.root);
        safeMode = OptLabUi.toggleCard(requireContext(), R.drawable.optlab_ic_safe_mode,
                getString(R.string.opt_lab_safe_mode),
                getString(R.string.opt_lab_safe_mode_summary), true);
        OptLabUi.addCard(page.content, safeMode.root);
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_changes_next_launch)));
        return page.root;
    }

    private OptLabUi.NavCard overviewCard(int icon, int title, int tab) {
        return OptLabUi.navCard(requireContext(), icon, getString(title), "",
                view -> navigateTopLevel(tab));
    }

    private View buildRuntimePage() {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        page.content.addView(OptLabUi.section(requireContext(),
                getString(R.string.opt_lab_general_controls)));
        generalMaster = addToggle(page, getString(R.string.opt_lab_general_master),
                getString(R.string.opt_lab_independent_note));
        page.content.addView(OptLabUi.section(requireContext(),
                getString(R.string.opt_lab_runtime_section)));
        quiet = addToggle(page, getString(R.string.opt_lab_quiet), "");
        buffered = addToggle(page, getString(R.string.opt_lab_buffered_stdio), "");
        sqlite = addToggle(page, getString(R.string.opt_lab_sqlite), "");
        box64 = OptLabUi.spinnerCard(requireContext(), R.drawable.optlab_ic_runtime,
                getString(R.string.opt_lab_box64), OptLabPreferences.Box64Policy.values());
        OptLabUi.addCard(page.content, box64.root);
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_general_restart_warning)));
        return page.root;
    }

    private View buildDisplayPage() {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        page.content.addView(OptLabUi.section(requireContext(),
                getString(R.string.opt_lab_android_section)));
        surface = addToggle(page, getString(R.string.opt_lab_surface), "");
        refresh = addToggle(page, getString(R.string.opt_lab_refresh), "");
        inputQueue = addToggle(page, getString(R.string.opt_lab_input_queue), "");
        analog = addToggle(page, getString(R.string.opt_lab_analog), "");
        coalesce = addToggle(page, getString(R.string.opt_lab_coalesce), "");
        mobileGlLog = addToggle(page, getString(R.string.opt_lab_mgl_log), "");
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_general_restart_warning)));
        return page.root;
    }

    private View buildBuild42Page() {
        modulePages.clear();
        moduleCards.clear();
        moduleDetailCards.clear();
        advancedNotes.clear();
        featureCards.clear();
        advancedOnlyViews.clear();
        build42Buttons.clear();
        build42Host = new FrameLayout(requireContext());
        OptLabUi.Page home = OptLabUi.page(requireContext());
        build42Home = home.root;
        home.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_build42_intro)));
        build42ProfileCard = OptLabUi.spinnerCard(requireContext(),
                R.drawable.optlab_ic_build42,
                getString(R.string.opt_lab_profile_build42), LAB_PROFILES);
        OptLabUi.addCard(home.content, build42ProfileCard.root);
        build42ProfileStatus = OptLabUi.note(requireContext(), "");
        home.content.addView(build42ProfileStatus);
        home.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_profile_build42_scope)));
        onlyBuild42 = addToggle(home, getString(R.string.opt_lab_only_build42),
                getString(R.string.opt_lab_build42_independent));
        advancedControls = addToggle(home, "Advanced controls",
                "Afișează controalele individuale; nu modifică singur nicio optimizare");
        customPresetCard = OptLabUi.navCard(requireContext(),
                R.drawable.optlab_ic_profile, "Preseturi custom Build 42", "",
                view -> navigate(Route.build42Presets(), true));
        OptLabUi.addCard(home.content, customPresetCard.root);
        home.content.addView(OptLabUi.section(requireContext(), "Module Build 42"));
        for (OptLabFeatureRegistry.Module module : BUILD42_MODULES) {
            OptLabUi.NavCard card = OptLabUi.navCard(requireContext(),
                    R.drawable.optlab_ic_build42, module.label, "",
                    view -> navigate(Route.build42Module(module), true));
            moduleCards.put(module, card);
            OptLabUi.addCard(home.content, card.root);
        }
        MaterialButton allOff = OptLabUi.actionButton(requireContext(),
                getString(R.string.opt_lab_build42_all_off));
        allOff.setOnClickListener(view -> {
            prefs.setBuild42ProductionAllOff();
            syncAll();
        });
        build42Buttons.add(allOff);
        home.content.addView(allOff);
        home.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_build42_restart_warning)));
        build42Host.addView(build42Home, matchParent());
        for (OptLabFeatureRegistry.Module module : BUILD42_MODULES) {
            View detail = buildModulePage(module);
            detail.setVisibility(View.GONE);
            modulePages.put(module, detail);
            build42Host.addView(detail, matchParent());
        }
        customPresetPage = buildCustomPresetManagerPage();
        customPresetPage.setVisibility(View.GONE);
        build42Host.addView(customPresetPage, matchParent());
        return build42Host;
    }

    private View buildModulePage(OptLabFeatureRegistry.Module module) {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        MaterialButton back = OptLabUi.actionButton(requireContext(), "← Build 42");
        back.setOnClickListener(view -> navigateBackOr(Route.build42Home()));
        page.content.addView(back);
        page.content.addView(OptLabUi.section(requireContext(), module.label));
        OptLabUi.NavCard status = OptLabUi.navCard(requireContext(),
                R.drawable.optlab_ic_build42, module.label, "", null);
        moduleDetailCards.put(module, status);
        OptLabUi.addCard(page.content, status.root);
        page.content.addView(OptLabUi.note(requireContext(), moduleDescription(module)));
        View advancedNote = OptLabUi.note(requireContext(),
                "Profilul Build 42 gestionează opțiunile de producție. Activează Advanced controls "
                        + "pentru toggle-uri individuale.");
        advancedNotes.put(module, advancedNote);
        page.content.addView(advancedNote);
        addFeatureGroup(page, module, OptLabFeatureRegistry.Maturity.STABLE,
                "Stable");

        if (hasExperimentalFeatures(module)) {
            OptLabUi.NavCard experimental = OptLabUi.navCard(requireContext(),
                    R.drawable.optlab_ic_experimental,
                    getString(R.string.opt_lab_experimental_card),
                    getString(R.string.opt_lab_experimental_module_link), view -> {
                        navigate(Route.experimentalModule(module), true);
                    });
            OptLabUi.addCard(page.content, experimental.root);
        }

        boolean internalFound = false;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.featuresForModule(module, true)) {
            if (feature.maturity != OptLabFeatureRegistry.Maturity.INTERNAL) continue;
            if (!internalFound) {
                page.content.addView(OptLabUi.section(requireContext(),
                        "Internal · control individual · fallback automat"));
                internalFound = true;
            }
            OptLabUi.ToggleCard card = addToggle(page, feature.title,
                    featureDescription(feature));
            featureCards.put(feature, card);
        }
        if (OptLabFeatureRegistry.featuresForModule(module, true).isEmpty()) {
            page.content.addView(OptLabUi.note(requireContext(),
                    "Rezervat pentru pachete viitoare. Nu există optimizări fabricate "
                            + "sau activate în acest modul."));
        }
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_changes_next_launch)));
        return page.root;
    }

    private View buildCustomPresetManagerPage() {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        MaterialButton back = OptLabUi.actionButton(requireContext(), "← Build 42");
        back.setOnClickListener(view -> navigateBackOr(Route.build42Home()));
        page.content.addView(back);
        page.content.addView(OptLabUi.section(requireContext(),
                "Preseturi custom · Build 42"));
        customPresetStatusCard = OptLabUi.navCard(requireContext(),
                R.drawable.optlab_ic_profile, "Stare preset", "", null);
        OptLabUi.addCard(page.content, customPresetStatusCard.root);

        customPresetSpinner = OptLabUi.spinnerCard(requireContext(),
                R.drawable.optlab_ic_profile, "Preset salvat",
                new OptLabCustomPresetStore.Preset[0]);
        OptLabUi.addCard(page.content, customPresetSpinner.root);
        customPresetName = OptLabUi.textFieldCard(requireContext(),
                "Nume pentru un preset nou",
                "Ex.: Telefon, Oraș, Exterior");
        OptLabUi.addCard(page.content, customPresetName.root);

        customPresetCreate = OptLabUi.actionButton(requireContext(),
                "Creează din configurația curentă");
        customPresetApply = OptLabUi.actionButton(requireContext(), "Aplică presetul selectat");
        customPresetUpdate = OptLabUi.actionButton(requireContext(),
                "Salvează starea curentă în preset");
        customPresetDelete = OptLabUi.actionButton(requireContext(),
                "Șterge presetul selectat");
        customPresetReset = OptLabUi.actionButton(requireContext(),
                "Repară lista de preseturi");
        customPresetCreate.setOnClickListener(view -> createCustomPreset());
        customPresetApply.setOnClickListener(view -> applyCustomPreset());
        customPresetUpdate.setOnClickListener(view -> updateCustomPreset());
        customPresetDelete.setOnClickListener(view -> deleteCustomPreset());
        customPresetReset.setOnClickListener(view -> resetCustomPresetCatalog());
        page.content.addView(customPresetCreate);
        page.content.addView(OptLabUi.actionRow(requireContext(),
                customPresetApply, customPresetUpdate));
        page.content.addView(customPresetDelete);
        page.content.addView(customPresetReset);
        page.content.addView(OptLabUi.note(requireContext(),
                "Presetul salvează toate controalele Build 42 vizibile, inclusiv Internal și "
                        + "Experimental Build 42. General, Native și Experimental independent "
                        + "rămân neatinse. Aplică încarcă valorile, iar Salvează actualizează "
                        + "presetul selectat. Ștergerea cere două apăsări."));
        return page.root;
    }

    private void addFeatureGroup(OptLabUi.Page page, OptLabFeatureRegistry.Module module,
                                 OptLabFeatureRegistry.Maturity maturity, String label) {
        boolean sectionAdded = false;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.featuresForModule(module, true)) {
            if (feature.maturity != maturity || !feature.advancedControl) continue;
            if (!sectionAdded) {
                View section = OptLabUi.section(requireContext(), label);
                advancedOnlyViews.add(section);
                page.content.addView(section);
                sectionAdded = true;
            }
            OptLabUi.ToggleCard card = addToggle(page,
                    feature.title + " · " + maturity.name(), featureDescription(feature));
            featureCards.put(feature, card);
            advancedOnlyViews.add(card.root);
        }
    }

    private View buildNativePage() {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        page.content.addView(OptLabUi.section(requireContext(),
                getString(R.string.opt_lab_native_stable)));
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.native_modules_intro)));
        clipper = addToggle(page, getString(R.string.native_modules_clipper),
                "Device validated · Build 42 ARM64 · fallback individual");
        OptLabUi.NavCard experimentalLink = OptLabUi.navCard(requireContext(),
                R.drawable.optlab_ic_experimental,
                getString(R.string.opt_lab_experimental_card),
                getString(R.string.opt_lab_native_experimental_link),
                view -> navigate(Route.experimentalModule(
                        OptLabFeatureRegistry.Module.NATIVE_ARM64), true));
        OptLabUi.addCard(page.content, experimentalLink.root);
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.native_modules_restart)));
        return page.root;
    }

    private View buildExperimentalPage() {
        experimentalPages.clear();
        experimentalModuleCards.clear();
        experimentalHost = new FrameLayout(requireContext());
        OptLabUi.Page home = OptLabUi.page(requireContext());
        experimentalHome = home.root;
        home.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_experimental_placeholder)));
        home.content.addView(OptLabUi.section(requireContext(),
                getString(R.string.opt_lab_experimental_categories)));
        experimentalNativeCard = OptLabUi.navCard(requireContext(),
                R.drawable.optlab_ic_native,
                getString(R.string.opt_lab_experimental_native), "",
                view -> navigate(Route.experimentalModule(
                        OptLabFeatureRegistry.Module.NATIVE_ARM64), true));
        OptLabUi.addCard(home.content, experimentalNativeCard.root);
        for (OptLabFeatureRegistry.Module module : BUILD42_MODULES) {
            if (!hasExperimentalFeatures(module)) continue;
            OptLabUi.NavCard card = OptLabUi.navCard(requireContext(),
                    R.drawable.optlab_ic_experimental, module.label, "",
                    view -> navigate(Route.experimentalModule(module), true));
            experimentalModuleCards.put(module, card);
            OptLabUi.addCard(home.content, card.root);
        }
        home.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_changes_next_launch)));
        experimentalHost.addView(experimentalHome, matchParent());

        View nativePage = buildExperimentalNativePage();
        nativePage.setVisibility(View.GONE);
        experimentalPages.put(OptLabFeatureRegistry.Module.NATIVE_ARM64, nativePage);
        experimentalHost.addView(nativePage, matchParent());
        for (OptLabFeatureRegistry.Module module : BUILD42_MODULES) {
            if (!hasExperimentalFeatures(module)) continue;
            View modulePage = buildExperimentalModulePage(module);
            modulePage.setVisibility(View.GONE);
            experimentalPages.put(module, modulePage);
            experimentalHost.addView(modulePage, matchParent());
        }
        return experimentalHost;
    }

    private View buildExperimentalNativePage() {
        OptLabUi.Page page = experimentalDetailPage(
                getString(R.string.opt_lab_experimental_native));
        lighting = addToggle(page, getString(R.string.native_modules_lighting),
                "EXPERIMENTAL · PoC device · implementare legacy non-parity · implicit OFF");
        pathfinding = addToggle(page, getString(R.string.native_modules_pathfinding),
                "Regresie comportamentală raportată · recomandat OFF");
        popMan = addToggle(page, getString(R.string.native_modules_popman),
                "Regresia completă pe dispozitiv este încă necesară · recomandat OFF");
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.native_modules_popman_unavailable)));
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_changes_next_launch)));
        return page.root;
    }

    private View buildExperimentalModulePage(OptLabFeatureRegistry.Module module) {
        OptLabUi.Page page = experimentalDetailPage(module.label);
        page.content.addView(OptLabUi.note(requireContext(), moduleDescription(module)));
        boolean found = false;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.featuresForModule(module, true)) {
            if (!isExperimentalFeature(feature) || !feature.advancedControl) continue;
            found = true;
            OptLabUi.ToggleCard card = addToggle(page, feature.title,
                    featureDescription(feature));
            featureCards.put(feature, card);
        }
        if (!found) {
            page.content.addView(OptLabUi.note(requireContext(),
                    getString(R.string.opt_lab_experimental_empty)));
        }
        page.content.addView(OptLabUi.note(requireContext(),
                getString(R.string.opt_lab_changes_next_launch)));
        return page.root;
    }

    private OptLabUi.Page experimentalDetailPage(String title) {
        OptLabUi.Page page = OptLabUi.page(requireContext());
        MaterialButton back = OptLabUi.actionButton(requireContext(),
                getString(R.string.opt_lab_experimental_back));
        back.setOnClickListener(view -> navigateBackOr(Route.experimentalHome()));
        page.content.addView(back);
        page.content.addView(OptLabUi.section(requireContext(), title));
        return page;
    }

    private void bindListeners() {
        generalProfileCard.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                OptLabPreferences.LabProfile selected = (OptLabPreferences.LabProfile)
                        parent.getItemAtPosition(position);
                boolean userGesture = generalProfileGesture;
                generalProfileGesture = false;
                if (!uiReady || updating || !userGesture
                        || selected == prefs.getGeneralProfile()) return;
                try {
                    prefs.setGeneralProfile(selected);
                } catch (RuntimeException error) {
                    showSettingsError(error);
                }
                syncAll();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        build42ProfileCard.spinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                          int position, long id) {
                        OptLabPreferences.LabProfile selected =
                                (OptLabPreferences.LabProfile)
                                        parent.getItemAtPosition(position);
                        boolean userGesture = build42ProfileGesture;
                        build42ProfileGesture = false;
                        if (!uiReady || updating || !userGesture
                                || selected == prefs.getBuild42LabProfile()) return;
                        try {
                            prefs.setBuild42LabProfile(selected);
                        } catch (RuntimeException error) {
                            showSettingsError(error);
                        }
                        syncAll();
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
        armProfileGesture(generalProfileCard, true);
        armProfileGesture(build42ProfileCard, false);
        customPresetSpinner.spinner.setOnItemSelectedListener(
                new AdapterView.OnItemSelectedListener() {
                    @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                          int position, long id) {
                        if (!uiReady || updating) return;
                        Object item = parent.getItemAtPosition(position);
                        if (!(item instanceof OptLabCustomPresetStore.Preset)) return;
                        OptLabCustomPresetStore.Preset preset =
                                (OptLabCustomPresetStore.Preset) item;
                        presetUiSelectedId = preset.getId();
                        pendingPresetDeleteId = null;
                        pendingPresetReset = false;
                        presetUiMessage = "";
                        updateSelectedPresetControls(prefs.isSafeModeEnabled(),
                                prefs.getCustomBuild42Presets(), preset);
                    }
                    @Override public void onNothingSelected(AdapterView<?> parent) {}
                });
        box64.spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                  int position, long id) {
                OptLabPreferences.Box64Policy selected = (OptLabPreferences.Box64Policy)
                        parent.getItemAtPosition(position);
                boolean userGesture = box64Gesture;
                box64Gesture = false;
                if (!uiReady || updating || !userGesture
                        || selected == prefs.getBox64Policy()) return;
                try {
                    prefs.setBox64Policy(selected);
                } catch (RuntimeException error) {
                    showSettingsError(error);
                }
                syncAll();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        armBox64Gesture();
        bind(safeMode, prefs::setSafeModeEnabled);
        bind(generalMaster, prefs::setMasterEnabled);
        bind(quiet, prefs::setQuietRuntime);
        bind(buffered, value -> prefs.setStdioMode(value
                ? OptLabPreferences.StdioMode.BUFFERED
                : OptLabPreferences.StdioMode.LEGACY));
        bind(sqlite, prefs::setSqliteAndroidNative);
        bind(surface, value -> prefs.setSurfaceMode(value
                ? OptLabPreferences.SurfaceMode.GEN_ACK
                : OptLabPreferences.SurfaceMode.LEGACY));
        bind(refresh, value -> prefs.setDisplayFpsHint(value
                ? OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH
                : OptLabPreferences.DisplayFpsHint.OFF));
        bind(inputQueue, value -> prefs.setInputQueueMode(value
                ? OptLabPreferences.InputQueueMode.MUTEX_SAFE
                : OptLabPreferences.InputQueueMode.LEGACY));
        bind(analog, prefs::setAnalogFilter);
        bind(coalesce, prefs::setInputCoalesce);
        bind(mobileGlLog, prefs::setMobileGlFileLogEnabled);
        bind(onlyBuild42, prefs::setOnlyBuild42);
        bind(advancedControls, prefs::setAdvancedControls);
        for (OptLabFeatureRegistry.Feature feature : featureCards.keySet()) {
            bind(featureCards.get(feature), value -> prefs.setFeatureEnabled(feature, value));
        }
        bind(lighting, nativePrefs::setLighting64Enabled);
        bind(clipper, nativePrefs::setPzClipperEnabled);
        bind(pathfinding, nativePrefs::setPathfindingEnabled);
        bind(popMan, nativePrefs::setPopManEnabled);
    }

    private void syncAll() {
        if (!uiReady) return;
        updating = true;
        boolean safe = prefs.isSafeModeEnabled();
        boolean general = prefs.isMasterEnabled();
        safeMode.toggle.setChecked(safe);
        safeMode.subtitle.setText(getString(safe
                ? R.string.opt_lab_safe_mode_active : R.string.opt_lab_safe_mode_summary));
        select(generalProfileCard, prefs.getGeneralProfile());
        generalProfileCard.setEnabled(!safe);
        generalMaster.toggle.setChecked(general);
        quiet.toggle.setChecked(prefs.isQuietRuntime());
        buffered.toggle.setChecked(prefs.getStdioMode()
                == OptLabPreferences.StdioMode.BUFFERED);
        sqlite.toggle.setChecked(prefs.isSqliteAndroidNative());
        select(box64, prefs.getBox64Policy());
        generalMaster.setEnabled(!safe);
        setEnabled(!safe && general, quiet, buffered, sqlite);
        box64.setEnabled(!safe && general);
        surface.toggle.setChecked(prefs.getSurfaceMode()
                == OptLabPreferences.SurfaceMode.GEN_ACK);
        refresh.toggle.setChecked(prefs.getDisplayFpsHint()
                == OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH);
        inputQueue.toggle.setChecked(prefs.getInputQueueMode()
                == OptLabPreferences.InputQueueMode.MUTEX_SAFE);
        analog.toggle.setChecked(prefs.isAnalogFilter());
        coalesce.toggle.setChecked(prefs.isInputCoalesce());
        mobileGlLog.toggle.setChecked(prefs.isMobileGlFileLogEnabled());
        setEnabled(!safe && general, surface, refresh, inputQueue, analog,
                coalesce, mobileGlLog);
        syncBuild42(safe);
        lighting.toggle.setChecked(nativePrefs.isLighting64Enabled());
        clipper.toggle.setChecked(nativePrefs.isPzClipperEnabled());
        pathfinding.toggle.setChecked(nativePrefs.isPathfindingEnabled());
        popMan.toggle.setChecked(nativePrefs.isPopManEnabled());
        setEnabled(!safe, lighting, clipper, pathfinding, popMan);

        int experimentalCount = (nativePrefs.isLighting64Enabled() ? 1 : 0)
                + (nativePrefs.isPathfindingEnabled() ? 1 : 0)
                + (nativePrefs.isPopManEnabled() ? 1 : 0)
                + (prefs.isChunkCp2cDirtyClear() ? 1 : 0)
                + prefs.experimentalBuild42EnabledCount();
        overviewRuntime.subtitle.setText(safe ? getString(R.string.opt_lab_safe_mode_state)
                : getString(R.string.opt_lab_active_count, runtimeCount()));
        overviewDisplay.subtitle.setText(safe ? getString(R.string.opt_lab_safe_mode_state)
                : getString(R.string.opt_lab_active_count, displayCount()));
        overviewBuild42.subtitle.setText(safe ? getString(R.string.opt_lab_safe_mode_state)
                : getString(R.string.opt_lab_build42_card_state,
                        prefs.isOnlyBuild42() ? "ON" : "OFF",
                        prefs.build42FeatureEnabledCount() == 0 ? "ALL OFF"
                                : prefs.build42FeatureEnabledCount() + " active"));
        overviewNative.subtitle.setText(nativePrefs.summary());
        overviewExperimental.subtitle.setText(safe
                ? getString(R.string.opt_lab_safe_mode_state)
                : getString(R.string.opt_lab_experimental_card_state, experimentalCount));
        updating = false;
    }

    private void syncBuild42(boolean safe) {
        boolean advanced = prefs.isAdvancedControls();
        select(build42ProfileCard, prefs.getBuild42LabProfile());
        build42ProfileCard.setEnabled(!safe);
        OptLabCustomPresetStore.Preset activePreset =
                prefs.getActiveCustomBuild42Preset();
        build42ProfileStatus.setText(build42ProfileLabel(activePreset));
        customPresetCard.subtitle.setText(customPresetHomeSummary(activePreset));
        onlyBuild42.toggle.setChecked(prefs.isOnlyBuild42());
        advancedControls.toggle.setChecked(advanced);
        setEnabled(!safe, onlyBuild42, advancedControls);
        for (MaterialButton button : build42Buttons) button.setEnabled(!safe);
        for (View view : advancedOnlyViews) {
            view.setVisibility(advanced ? View.VISIBLE : View.GONE);
        }
        for (OptLabFeatureRegistry.Feature feature : featureCards.keySet()) {
            OptLabUi.ToggleCard card = featureCards.get(feature);
            card.toggle.setChecked(prefs.isFeatureEnabled(feature));
            card.setEnabled(!safe);
            card.subtitle.setText(featureDescription(feature));
        }
        for (OptLabFeatureRegistry.Module module : BUILD42_MODULES) {
            String summary = moduleSummary(module, safe);
            moduleCards.get(module).subtitle.setText(summary);
            moduleDetailCards.get(module).subtitle.setText(summary);
            advancedNotes.get(module).setVisibility(advanced ? View.GONE : View.VISIBLE);
            OptLabUi.NavCard experimental = experimentalModuleCards.get(module);
            if (experimental != null) {
                experimental.subtitle.setText(experimentalModuleSummary(module, safe));
            }
        }
        if (experimentalNativeCard != null) {
            int nativeActive = (nativePrefs.isLighting64Enabled() ? 1 : 0)
                    + (nativePrefs.isPathfindingEnabled() ? 1 : 0)
                    + (nativePrefs.isPopManEnabled() ? 1 : 0);
            experimentalNativeCard.subtitle.setText(safe ? "Safe Mode"
                    : nativeActive + "/3 active · implicit OFF");
        }
        syncCustomPresetManager(safe);
    }

    private String build42ProfileLabel(OptLabCustomPresetStore.Preset activePreset) {
        String label;
        if (activePreset == null) {
            label = "Profil activ: " + prefs.getBuild42LabProfile();
        } else if (prefs.isActiveCustomBuild42PresetModified()) {
            label = "Modified · based on " + activePreset.getName();
        } else {
            label = "Custom · " + activePreset.getName();
        }
        return prefs.isSafeModeEnabled() ? "Safe Mode · păstrează " + label : label;
    }

    private String customPresetHomeSummary(OptLabCustomPresetStore.Preset activePreset) {
        if (prefs.hasCustomBuild42PresetStorageProblem()) {
            return "Catalog indisponibil · datele existente sunt păstrate";
        }
        int count = prefs.getCustomBuild42Presets().size();
        if (activePreset != null) {
            return (prefs.isActiveCustomBuild42PresetModified()
                    ? "Modified · based on " : "Custom · ") + activePreset.getName()
                    + " · " + count + (count == 1 ? " preset" : " preseturi");
        }
        return count == 0 ? "Niciun preset salvat"
                : count + " preseturi salvate · niciunul aplicat";
    }

    private void syncCustomPresetManager(boolean safe) {
        List<OptLabCustomPresetStore.Preset> presets =
                prefs.getCustomBuild42Presets();
        OptLabCustomPresetStore.Preset selected = findPreset(presets, presetUiSelectedId);
        if (selected == null) {
            OptLabCustomPresetStore.Preset active = prefs.getActiveCustomBuild42Preset();
            selected = active == null ? null : findPreset(presets, active.getId());
        }
        if (selected == null && !presets.isEmpty()) selected = presets.get(0);

        customPresetSpinner.adapter.clear();
        customPresetSpinner.adapter.addAll(presets);
        customPresetSpinner.adapter.notifyDataSetChanged();
        if (selected != null) {
            int position = presetPosition(presets, selected.getId());
            if (position >= 0
                    && customPresetSpinner.spinner.getSelectedItemPosition() != position) {
                customPresetSpinner.spinner.setSelection(position, false);
            }
            presetUiSelectedId = selected.getId();
        } else {
            presetUiSelectedId = null;
        }
        updateSelectedPresetControls(safe, presets, selected);
    }

    private void updateSelectedPresetControls(boolean safe,
                                              List<OptLabCustomPresetStore.Preset> presets,
                                              OptLabCustomPresetStore.Preset selected) {
        boolean storageProblem = prefs.hasCustomBuild42PresetStorageProblem();
        boolean mutable = !safe && !storageProblem;
        boolean hasSelection = selected != null;
        customPresetSpinner.setEnabled(mutable && hasSelection);
        customPresetName.setEnabled(mutable);
        customPresetCreate.setEnabled(mutable);
        customPresetApply.setEnabled(mutable && hasSelection);
        customPresetUpdate.setEnabled(mutable && hasSelection);
        customPresetDelete.setEnabled(mutable && hasSelection);
        customPresetReset.setVisibility(storageProblem ? View.VISIBLE : View.GONE);
        customPresetReset.setEnabled(!safe && storageProblem);
        customPresetDelete.setText(hasSelection
                && selected.getId().equals(pendingPresetDeleteId)
                ? "Confirmă ștergerea" : "Șterge presetul selectat");
        customPresetReset.setText(pendingPresetReset
                ? "Confirmă repararea listei" : "Repară lista de preseturi");

        String state;
        if (storageProblem) {
            state = "Catalog indisponibil · date păstrate · "
                    + prefs.getCustomBuild42PresetStorageProblem();
        } else if (selected == null) {
            state = "Niciun preset salvat · configurația curentă nu este afectată";
        } else {
            OptLabCustomPresetStore.Preset active = prefs.getActiveCustomBuild42Preset();
            if (active != null && active.getId().equals(selected.getId())) {
                state = prefs.isActiveCustomBuild42PresetModified()
                        ? "Modified · based on " + selected.getName()
                        : "Custom · " + selected.getName() + " · exact";
            } else {
                state = "Selectat: " + selected.getName() + " · nu este aplicat";
            }
            state += " · " + presets.size()
                    + (presets.size() == 1 ? " preset salvat" : " preseturi salvate");
        }
        if (safe) state = "Safe Mode · controale blocate · " + state;
        if (!presetUiMessage.isEmpty()) state += " · " + presetUiMessage;
        customPresetStatusCard.subtitle.setText(state);
    }

    private void createCustomPreset() {
        try {
            OptLabCustomPresetStore.Preset preset = prefs.createCustomBuild42Preset(
                    requestedPresetName());
            customPresetName.input.setText("");
            completePresetAction(preset, "Preset creat");
        } catch (RuntimeException error) {
            failPresetAction(error);
        }
    }

    private void applyCustomPreset() {
        OptLabCustomPresetStore.Preset selected = selectedPreset();
        if (selected == null) {
            failPresetAction("Selectează un preset");
            return;
        }
        try {
            completePresetAction(prefs.applyCustomBuild42Preset(selected.getId()),
                    "Preset aplicat · restart necesar");
        } catch (RuntimeException error) {
            failPresetAction(error);
        }
    }

    private void updateCustomPreset() {
        OptLabCustomPresetStore.Preset selected = selectedPreset();
        if (selected == null) {
            failPresetAction("Selectează un preset");
            return;
        }
        try {
            completePresetAction(prefs.updateCustomBuild42Preset(selected.getId()),
                    "Preset actualizat");
        } catch (RuntimeException error) {
            failPresetAction(error);
        }
    }

    private void deleteCustomPreset() {
        OptLabCustomPresetStore.Preset selected = selectedPreset();
        if (selected == null) {
            failPresetAction("Selectează un preset");
            return;
        }
        if (!selected.getId().equals(pendingPresetDeleteId)) {
            pendingPresetDeleteId = selected.getId();
            presetUiMessage = "Apasă din nou pentru ștergerea definitivă";
            updateSelectedPresetControls(prefs.isSafeModeEnabled(),
                    prefs.getCustomBuild42Presets(), selected);
            return;
        }
        try {
            prefs.deleteCustomBuild42Preset(selected.getId());
            presetUiSelectedId = null;
            pendingPresetDeleteId = null;
            presetUiMessage = "Preset șters";
            syncAll();
        } catch (RuntimeException error) {
            failPresetAction(error);
        }
    }

    private void resetCustomPresetCatalog() {
        if (!prefs.hasCustomBuild42PresetStorageProblem()) return;
        if (!pendingPresetReset) {
            pendingPresetReset = true;
            presetUiMessage = "Repararea șterge numai lista coruptă, nu setările active";
            updateSelectedPresetControls(prefs.isSafeModeEnabled(),
                    prefs.getCustomBuild42Presets(), selectedPreset());
            return;
        }
        try {
            prefs.resetCustomBuild42PresetCatalog();
            presetUiSelectedId = null;
            pendingPresetDeleteId = null;
            pendingPresetReset = false;
            presetUiMessage = "Lista de preseturi a fost reparată; setările active au rămas intacte";
            syncAll();
        } catch (RuntimeException error) {
            failPresetAction(error);
        }
    }

    private void completePresetAction(OptLabCustomPresetStore.Preset preset,
                                      String message) {
        presetUiSelectedId = preset.getId();
        pendingPresetDeleteId = null;
        pendingPresetReset = false;
        presetUiMessage = message;
        syncAll();
    }

    private void failPresetAction(RuntimeException error) {
        String message = error.getMessage();
        failPresetAction(message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message);
    }

    private void failPresetAction(String message) {
        pendingPresetDeleteId = null;
        pendingPresetReset = false;
        presetUiMessage = "Eroare: " + message;
        updateSelectedPresetControls(prefs.isSafeModeEnabled(),
                prefs.getCustomBuild42Presets(), selectedPreset());
    }

    private String requestedPresetName() {
        CharSequence value = customPresetName.input.getText();
        return value == null ? "" : value.toString();
    }

    private OptLabCustomPresetStore.Preset selectedPreset() {
        return findPreset(prefs.getCustomBuild42Presets(), presetUiSelectedId);
    }

    private static OptLabCustomPresetStore.Preset findPreset(
            List<OptLabCustomPresetStore.Preset> presets, String id) {
        if (id == null) return null;
        for (OptLabCustomPresetStore.Preset preset : presets) {
            if (id.equals(preset.getId())) return preset;
        }
        return null;
    }

    private static int presetPosition(List<OptLabCustomPresetStore.Preset> presets,
                                      String id) {
        for (int index = 0; index < presets.size(); index++) {
            if (id.equals(presets.get(index).getId())) return index;
        }
        return -1;
    }

    private String experimentalModuleSummary(OptLabFeatureRegistry.Module module,
                                             boolean safeMode) {
        int total = 0;
        int active = 0;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.featuresForModule(module, true)) {
            if (!isExperimentalFeature(feature)) continue;
            total++;
            if (prefs.isFeatureEnabled(feature)) active++;
        }
        return safeMode ? "Safe Mode · " + total + " păstrate"
                : active + "/" + total + " active · implicit OFF";
    }

    private String moduleSummary(OptLabFeatureRegistry.Module module, boolean safeMode) {
        int stable = OptLabFeatureRegistry.count(module,
                OptLabFeatureRegistry.Maturity.STABLE);
        int experimental = OptLabFeatureRegistry.count(module,
                OptLabFeatureRegistry.Maturity.EXPERIMENTAL);
        int internal = OptLabFeatureRegistry.count(module,
                OptLabFeatureRegistry.Maturity.INTERNAL);
        int active = 0;
        boolean recommended = true;
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.featuresForModule(module, true)) {
            boolean enabled = prefs.isFeatureEnabled(feature);
            if (enabled) active++;
            if (enabled != feature.recommendedProfile) recommended = false;
        }
        if (stable + experimental + internal == 0) {
            return "0 active · rezervat pentru pachete viitoare";
        }
        String state = safeMode ? "Safe Mode" : active + " active";
        return String.format(Locale.ROOT,
                "%d stabile · %d experimentale · %d interne · %s · Recommended: %s "
                        + "· gate per-feature",
                stable, experimental, internal, state, recommended ? "ON" : "CUSTOM");
    }

    private static String featureDescription(OptLabFeatureRegistry.Feature feature) {
        return maturityLabel(feature.maturity) + " · " + validationLabel(feature.validation)
                + (feature.restartRequired ? " · restart" : "")
                + " · fallback: " + feature.fallback;
    }

    private static String maturityLabel(OptLabFeatureRegistry.Maturity maturity) {
        switch (maturity) {
            case STABLE: return "STABLE";
            case EXPERIMENTAL: return "EXPERIMENTAL · implicit OFF";
            case INTERNAL: return "INTERNAL · control individual";
            case ARCHIVED: return "ARCHIVED";
            default: throw new AssertionError(maturity);
        }
    }

    private static String validationLabel(OptLabFeatureRegistry.Validation validation) {
        switch (validation) {
            case HOST_VALIDATED: return "host validated";
            case DEVICE_POC_VALIDATED: return "PoC device validated";
            case DEVICE_VALIDATED: return "device validated";
            case NEEDS_VALIDATION: return "necesită device test";
            case REJECTED: return "respins";
            default: throw new AssertionError(validation);
        }
    }

    private static String moduleDescription(OptLabFeatureRegistry.Module module) {
        switch (module) {
            case MAIN_LOOP_PACING:
                return "Cadru principal, pacing și bugete de timp. Schimbările de ritm "
                        + "rămân experimentale până la validare frametime.";
            case WORLD_STREAM_CHUNK:
                return "Încărcare world/chunk, vecini și vehicule. B42.20 și 42.20.3 "
                        + "sunt verificate; hotfixurile apropiate folosesc probe și fallback.";
            case FBO_RENDER_CELL:
                return "Pregătirea FBO și Render Cell. Un fastpath poate reutiliza doar "
                        + "date fără efecte secundare; lucrul dirty nu este sărit sau amânat.";
            case RENDER_HOTPATH:
                return "Elimină lucru redundant din render fără a modifica imaginea; "
                        + "fiecare mecanism are invalidare sau fallback individual.";
            case MODEL_RINGBUFFER:
                return "Construirea bufferelor și echivalența stărilor de model. "
                        + "Bulk packing agresiv rămâne arhivat și nu poate fi lansat.";
            case SHADER_UNIFORMS:
                return "Lookup-uri și upload-uri redundante de shader/uniform. "
                        + "Fastpathurile semantice sigure sunt interne.";
            case MEMORY_ALLOCATION:
                return "Spațiu rezervat pentru optimizări de alocare demonstrate în "
                        + "pachetele viitoare; momentan nu activează nimic.";
            default:
                return module.label;
        }
    }

    private static boolean isExperimentalFeature(OptLabFeatureRegistry.Feature feature) {
        return feature.isVisible()
                && (feature.maturity == OptLabFeatureRegistry.Maturity.EXPERIMENTAL
                || feature.category == OptLabFeatureRegistry.Category.EXPERIMENTAL);
    }

    private static boolean hasExperimentalFeatures(OptLabFeatureRegistry.Module module) {
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.featuresForModule(module, true)) {
            if (isExperimentalFeature(feature)) return true;
        }
        return false;
    }

    private void showBuild42Module(OptLabFeatureRegistry.Module selected) {
        if (build42Home == null) return;
        build42Home.setVisibility(View.GONE);
        if (customPresetPage != null) customPresetPage.setVisibility(View.GONE);
        for (OptLabFeatureRegistry.Module module : BUILD42_MODULES) {
            View page = modulePages.get(module);
            if (page != null) {
                page.setVisibility(module == selected ? View.VISIBLE : View.GONE);
                if (module == selected) page.scrollTo(0, 0);
            }
        }
    }

    private void showBuild42Home() {
        if (build42Home == null) return;
        build42Home.setVisibility(View.VISIBLE);
        build42Home.scrollTo(0, 0);
        for (View page : modulePages.values()) page.setVisibility(View.GONE);
        if (customPresetPage != null) customPresetPage.setVisibility(View.GONE);
    }

    private void showCustomPresetManager() {
        if (build42Home == null || customPresetPage == null) return;
        build42Home.setVisibility(View.GONE);
        for (View page : modulePages.values()) page.setVisibility(View.GONE);
        customPresetPage.setVisibility(View.VISIBLE);
        customPresetPage.scrollTo(0, 0);
    }

    private void showExperimentalModule(OptLabFeatureRegistry.Module selected) {
        if (experimentalHome == null) return;
        experimentalHome.setVisibility(View.GONE);
        for (OptLabFeatureRegistry.Module module : experimentalPages.keySet()) {
            View page = experimentalPages.get(module);
            if (page != null) {
                page.setVisibility(module == selected ? View.VISIBLE : View.GONE);
                if (module == selected) page.scrollTo(0, 0);
            }
        }
    }

    private void showExperimentalNative() {
        showExperimentalModule(OptLabFeatureRegistry.Module.NATIVE_ARM64);
    }

    private void showExperimentalHome() {
        if (experimentalHome == null) return;
        experimentalHome.setVisibility(View.VISIBLE);
        experimentalHome.scrollTo(0, 0);
        for (View page : experimentalPages.values()) page.setVisibility(View.GONE);
    }

    private OptLabUi.ToggleCard addToggle(OptLabUi.Page page, String title,
                                           String subtitle) {
        OptLabUi.ToggleCard card = OptLabUi.toggleCard(requireContext(), title, subtitle);
        OptLabUi.addCard(page.content, card.root);
        return card;
    }

    private void bind(OptLabUi.ToggleCard card, BooleanSetter setter) {
        card.toggle.setOnCheckedChangeListener((view, checked) -> {
            if (!uiReady || updating) return;
            try {
                setter.set(checked);
            } catch (RuntimeException error) {
                showSettingsError(error);
            }
            syncAll();
        });
    }

    private void armProfileGesture(OptLabUi.SpinnerCard<?> card, boolean general) {
        card.spinner.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (general) generalProfileGesture = true;
                else build42ProfileGesture = true;
            }
            return false;
        });
        card.spinner.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE)) {
                if (general) generalProfileGesture = true;
                else build42ProfileGesture = true;
            }
            return false;
        });
    }

    private void armBox64Gesture() {
        box64.spinner.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) box64Gesture = true;
            return false;
        });
        box64.spinner.setOnKeyListener((view, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                    && (keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                    || keyCode == KeyEvent.KEYCODE_SPACE)) {
                box64Gesture = true;
            }
            return false;
        });
    }

    private void showSettingsError(RuntimeException error) {
        String detail = error.getMessage();
        Toast.makeText(requireContext(), "OPT-LAB: setarea nu a fost salvată"
                + (detail == null || detail.isEmpty() ? "" : " · " + detail),
                Toast.LENGTH_LONG).show();
    }

    private void addTab(int label) { tabs.addTab(tabs.newTab().setText(label)); }

    private void navigateTopLevel(int tab) {
        routeHistory.clear();
        Route target = tab == TAB_BUILD42 ? Route.build42Home()
                : tab == TAB_EXPERIMENTAL ? Route.experimentalHome() : Route.root(tab);
        navigate(target, false);
    }

    private void navigate(Route target, boolean rememberCurrent) {
        if (pages == null || tabs == null || target == null) return;
        if (rememberCurrent && currentRoute != null && !currentRoute.samePage(target)) {
            routeHistory.push(currentRoute);
        }
        renderRoute(target);
    }

    private boolean navigateBack() {
        if (routeHistory.isEmpty()) return false;
        renderRoute(routeHistory.pop());
        return true;
    }

    private void navigateBackOr(Route fallback) {
        if (!navigateBack()) navigate(fallback, false);
    }

    private void renderRoute(Route route) {
        if (pages == null || tabs == null) return;
        TabLayout.Tab selected = tabs.getTabAt(route.tab);
        if (selected != null && tabs.getSelectedTabPosition() != route.tab) {
            internalTabSelection = true;
            selected.select();
            // TabLayout normally dispatches synchronously.  If it does not, the selected route
            // still renders here and the delayed callback is ignored once.
        }
        for (int index = 0; index < pages.length; index++) {
            pages[index].setVisibility(index == route.tab ? View.VISIBLE : View.GONE);
        }
        switch (route.kind) {
            case BUILD42_HOME:
                showBuild42Home();
                break;
            case BUILD42_MODULE:
                showBuild42Module(route.module);
                break;
            case BUILD42_PRESETS:
                showCustomPresetManager();
                break;
            case EXPERIMENTAL_HOME:
                showExperimentalHome();
                break;
            case EXPERIMENTAL_MODULE:
                showExperimentalModule(route.module);
                break;
            case ROOT:
                pages[route.tab].scrollTo(0, 0);
                break;
            default:
                throw new AssertionError(route.kind);
        }
        currentRoute = route;
    }

    private enum RouteKind {
        ROOT,
        BUILD42_HOME,
        BUILD42_MODULE,
        BUILD42_PRESETS,
        EXPERIMENTAL_HOME,
        EXPERIMENTAL_MODULE
    }

    private static final class Route {
        final int tab;
        final RouteKind kind;
        final OptLabFeatureRegistry.Module module;

        private Route(int tab, RouteKind kind, OptLabFeatureRegistry.Module module) {
            this.tab = tab;
            this.kind = kind;
            this.module = module;
        }

        static Route root(int tab) { return new Route(tab, RouteKind.ROOT, null); }
        static Route build42Home() {
            return new Route(TAB_BUILD42, RouteKind.BUILD42_HOME, null);
        }
        static Route build42Module(OptLabFeatureRegistry.Module module) {
            return new Route(TAB_BUILD42, RouteKind.BUILD42_MODULE, module);
        }
        static Route build42Presets() {
            return new Route(TAB_BUILD42, RouteKind.BUILD42_PRESETS, null);
        }
        static Route experimentalHome() {
            return new Route(TAB_EXPERIMENTAL, RouteKind.EXPERIMENTAL_HOME, null);
        }
        static Route experimentalModule(OptLabFeatureRegistry.Module module) {
            return new Route(TAB_EXPERIMENTAL, RouteKind.EXPERIMENTAL_MODULE, module);
        }

        boolean samePage(Route other) {
            return other != null && tab == other.tab && kind == other.kind
                    && module == other.module;
        }
    }

    private int runtimeCount() {
        int count = 0;
        if (prefs.isQuietRuntime()) count++;
        if (prefs.getStdioMode() == OptLabPreferences.StdioMode.BUFFERED) count++;
        if (prefs.isSqliteAndroidNative()) count++;
        if (prefs.getBox64Policy() != OptLabPreferences.Box64Policy.LEGACY_3_0) count++;
        return count;
    }

    private int displayCount() {
        int count = 0;
        if (prefs.getSurfaceMode() == OptLabPreferences.SurfaceMode.GEN_ACK) count++;
        if (prefs.getDisplayFpsHint() == OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH) count++;
        if (prefs.getInputQueueMode() == OptLabPreferences.InputQueueMode.MUTEX_SAFE) count++;
        if (prefs.isAnalogFilter()) count++;
        if (prefs.isInputCoalesce()) count++;
        if (prefs.isMobileGlFileLogEnabled()) count++;
        return count;
    }

    private static void setEnabled(boolean enabled, OptLabUi.ToggleCard... cards) {
        for (OptLabUi.ToggleCard card : cards) card.setEnabled(enabled);
    }

    private static FrameLayout.LayoutParams matchParent() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private static <T> void select(OptLabUi.SpinnerCard<T> card, T value) {
        int position = card.adapter.getPosition(value);
        if (position >= 0 && card.spinner.getSelectedItemPosition() != position) {
            card.spinner.setSelection(position, false);
        }
    }

    private interface BooleanSetter { void set(boolean value); }
}
