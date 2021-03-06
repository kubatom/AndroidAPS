package info.nightscout.androidaps.plugins.ConstraintsSafety;

import info.nightscout.androidaps.MainApp;
import info.nightscout.androidaps.R;
import info.nightscout.androidaps.data.ConstraintChecker;
import info.nightscout.androidaps.data.Profile;
import info.nightscout.androidaps.interfaces.BgSourceInterface;
import info.nightscout.androidaps.interfaces.Constraint;
import info.nightscout.androidaps.interfaces.ConstraintsInterface;
import info.nightscout.androidaps.interfaces.PluginBase;
import info.nightscout.androidaps.interfaces.PluginDescription;
import info.nightscout.androidaps.interfaces.PluginType;
import info.nightscout.androidaps.plugins.ConfigBuilder.ConfigBuilderPlugin;
import info.nightscout.androidaps.plugins.OpenAPSAMA.OpenAPSAMAPlugin;
import info.nightscout.androidaps.plugins.OpenAPSMA.OpenAPSMAPlugin;
import info.nightscout.androidaps.plugins.OpenAPSSMB.OpenAPSSMBPlugin;
import info.nightscout.androidaps.plugins.Overview.events.EventNewNotification;
import info.nightscout.androidaps.plugins.Overview.notifications.Notification;
import info.nightscout.utils.DecimalFormatter;
import info.nightscout.utils.HardLimits;
import info.nightscout.utils.Round;
import info.nightscout.utils.SP;

/**
 * Created by mike on 05.08.2016.
 */
public class SafetyPlugin extends PluginBase implements ConstraintsInterface {

    static SafetyPlugin plugin = null;

    public static SafetyPlugin getPlugin() {
        if (plugin == null)
            plugin = new SafetyPlugin();
        return plugin;
    }

    public SafetyPlugin() {
        super(new PluginDescription()
                .mainType(PluginType.CONSTRAINTS)
                .neverVisible(true)
                .alwaysEnabled(true)
                .showInList(false)
                .pluginName(R.string.safety)
                .preferencesId(R.xml.pref_safety)
        );
    }

    /**
     * Constraints interface
     **/
    @Override
    public Constraint<Boolean> isLoopInvokationAllowed(Constraint<Boolean> value) {
        if (!ConfigBuilderPlugin.getActivePump().getPumpDescription().isTempBasalCapable)
            value.set(false, MainApp.gs(R.string.pumpisnottempbasalcapable), this);
        return value;
    }

    @Override
    public Constraint<Boolean> isClosedLoopAllowed(Constraint<Boolean> value) {
        String mode = SP.getString("aps_mode", "open");
        if (!mode.equals("closed"))
            value.set(false, MainApp.gs(R.string.closedmodedisabledinpreferences), this);

        if (!MainApp.isEngineeringModeOrRelease()) {
            if (value.value()) {
                Notification n = new Notification(Notification.TOAST_ALARM, MainApp.gs(R.string.closed_loop_disabled_on_dev_branch), Notification.NORMAL);
                MainApp.bus().post(new EventNewNotification(n));
            }
            value.set(false, MainApp.gs(R.string.closed_loop_disabled_on_dev_branch), this);
        }

        return value;
    }

    @Override
    public Constraint<Boolean> isAutosensModeEnabled(Constraint<Boolean> value) {
        boolean enabled = SP.getBoolean(R.string.key_openapsama_useautosens, false);
        if (!enabled)
            value.set(false, MainApp.gs(R.string.autosensdisabledinpreferences), this);
        return value;
    }

    @Override
    public Constraint<Boolean> isSMBModeEnabled(Constraint<Boolean> value) {
        boolean enabled = SP.getBoolean(R.string.key_use_smb, false);
        if (!enabled)
            value.set(false, MainApp.gs(R.string.smbdisabledinpreferences), this);
        ConstraintChecker constraintChecker = MainApp.getConstraintChecker();
        Constraint<Boolean> closedLoop = constraintChecker.isClosedLoopAllowed();
        if (!closedLoop.value())
            value.set(false, MainApp.gs(R.string.smbnotallowedinopenloopmode), this);
        return value;
    }

    @Override
    public Constraint<Boolean> isAdvancedFilteringEnabled(Constraint<Boolean> value) {
        BgSourceInterface bgSource = MainApp.getConfigBuilder().getActiveBgSource();

        if (bgSource != null) {
            if (!bgSource.advancedFilteringSupported())
                value.set(false, MainApp.gs(R.string.smbalwaysdisabled), this);
        }
        return value;
    }

    @Override
    public Constraint<Double> applyBasalConstraints(Constraint<Double> absoluteRate, Profile profile) {

        absoluteRate.setIfGreater(0d, String.format(MainApp.gs(R.string.limitingbasalratio), 0d, MainApp.gs(R.string.itmustbepositivevalue)), this);

        double maxBasal = SP.getDouble(R.string.key_openapsma_max_basal, 1d);
        absoluteRate.setIfSmaller(maxBasal, String.format(MainApp.gs(R.string.limitingbasalratio), maxBasal, MainApp.gs(R.string.maxvalueinpreferences)), this);

        // Check percentRate but absolute rate too, because we know real current basal in pump
        Double maxBasalMult = SP.getDouble(R.string.key_openapsama_current_basal_safety_multiplier, 4d);
        double maxFromBasalMult = Math.floor(maxBasalMult * profile.getBasal() * 100) / 100;
        absoluteRate.setIfSmaller(maxFromBasalMult, String.format(MainApp.gs(R.string.limitingbasalratio), maxFromBasalMult, MainApp.gs(R.string.maxbasalmultiplier)), this);

        Double maxBasalFromDaily = SP.getDouble(R.string.key_openapsama_max_daily_safety_multiplier, 3d);
        double maxFromDaily = Math.floor(profile.getMaxDailyBasal() * maxBasalFromDaily * 100) / 100;
        absoluteRate.setIfSmaller(maxFromDaily, String.format(MainApp.gs(R.string.limitingbasalratio), maxFromDaily, MainApp.gs(R.string.maxdailybasalmultiplier)), this);

        absoluteRate.setIfSmaller(HardLimits.maxBasal(), String.format(MainApp.gs(R.string.limitingbasalratio), HardLimits.maxBasal(), MainApp.gs(R.string.hardlimit)), this);
        return absoluteRate;
    }

    @Override
    public Constraint<Integer> applyBasalPercentConstraints(Constraint<Integer> percentRate, Profile profile) {

        Double currentBasal = profile.getBasal();
        Double absoluteRate = currentBasal * ((double) percentRate.originalValue() / 100);

        percentRate.addReason("Percent rate " + percentRate.originalValue() + "% recalculated to " + DecimalFormatter.to2Decimal(absoluteRate) + " U/h with current basal " + DecimalFormatter.to2Decimal(currentBasal) + " U/h", this);

        Constraint<Double> absoluteConstraint = new Constraint<>(absoluteRate);
        applyBasalConstraints(absoluteConstraint, profile);
        percentRate.copyReasons(absoluteConstraint);

        Integer percentRateAfterConst = Double.valueOf(absoluteConstraint.value() / currentBasal * 100).intValue();
        if (percentRateAfterConst < 100)
            percentRateAfterConst = Round.ceilTo((double) percentRateAfterConst, 10d).intValue();
        else percentRateAfterConst = Round.floorTo((double) percentRateAfterConst, 10d).intValue();

        percentRate.set(percentRateAfterConst, String.format(MainApp.gs(R.string.limitingpercentrate), percentRateAfterConst, MainApp.gs(R.string.pumplimit)), this);

        return percentRate;
    }

    @Override
    public Constraint<Double> applyBolusConstraints(Constraint<Double> insulin) {
        insulin.setIfGreater(0d, String.format(MainApp.gs(R.string.limitingbolus), 0d, MainApp.gs(R.string.itmustbepositivevalue)), this);

        Double maxBolus = SP.getDouble(R.string.key_treatmentssafety_maxbolus, 3d);
        insulin.setIfSmaller(maxBolus, String.format(MainApp.gs(R.string.limitingbolus), maxBolus, MainApp.gs(R.string.maxvalueinpreferences)), this);

        insulin.setIfSmaller(HardLimits.maxBolus(), String.format(MainApp.gs(R.string.limitingbolus), HardLimits.maxBolus(), MainApp.gs(R.string.hardlimit)), this);
        return insulin;
    }

    @Override
    public Constraint<Integer> applyCarbsConstraints(Constraint<Integer> carbs) {
        carbs.setIfGreater(0, String.format(MainApp.gs(R.string.limitingcarbs), 0, MainApp.gs(R.string.itmustbepositivevalue)), this);

        Integer maxCarbs = SP.getInt(R.string.key_treatmentssafety_maxcarbs, 48);
        carbs.setIfSmaller(maxCarbs, String.format(MainApp.gs(R.string.limitingcarbs), maxCarbs, MainApp.gs(R.string.maxvalueinpreferences)), this);

        return carbs;
    }

    @Override
    public Constraint<Double> applyMaxIOBConstraints(Constraint<Double> maxIob) {
        double maxIobPref;
        if (OpenAPSSMBPlugin.getPlugin().isEnabled(PluginType.APS))
            maxIobPref = SP.getDouble(R.string.key_openapssmb_max_iob, 3d);
        else
            maxIobPref = SP.getDouble(R.string.key_openapsma_max_iob, 1.5d);
        maxIob.setIfSmaller(maxIobPref, String.format(MainApp.gs(R.string.limitingiob), maxIobPref, MainApp.gs(R.string.maxvalueinpreferences)), this);

        if (OpenAPSMAPlugin.getPlugin().isEnabled(PluginType.APS))
            maxIob.setIfSmaller(HardLimits.maxIobAMA(), String.format(MainApp.gs(R.string.limitingiob), HardLimits.maxIobAMA(), MainApp.gs(R.string.hardlimit)), this);
        if (OpenAPSAMAPlugin.getPlugin().isEnabled(PluginType.APS))
            maxIob.setIfSmaller(HardLimits.maxIobAMA(), String.format(MainApp.gs(R.string.limitingiob), HardLimits.maxIobAMA(), MainApp.gs(R.string.hardlimit)), this);
        if (OpenAPSSMBPlugin.getPlugin().isEnabled(PluginType.APS))
            maxIob.setIfSmaller(HardLimits.maxIobSMB(), String.format(MainApp.gs(R.string.limitingiob), HardLimits.maxIobSMB(), MainApp.gs(R.string.hardlimit)), this);
        return maxIob;
    }

}
