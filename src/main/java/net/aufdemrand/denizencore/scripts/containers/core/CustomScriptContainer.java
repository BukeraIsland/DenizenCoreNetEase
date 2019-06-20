package net.aufdemrand.denizencore.scripts.containers.core;

import net.aufdemrand.denizencore.DenizenCore;
import net.aufdemrand.denizencore.interfaces.ContextSource;
import net.aufdemrand.denizencore.objects.CustomObject;
import net.aufdemrand.denizencore.objects.Element;
import net.aufdemrand.denizencore.objects.dObject;
import net.aufdemrand.denizencore.scripts.ScriptBuilder;
import net.aufdemrand.denizencore.scripts.ScriptEntry;
import net.aufdemrand.denizencore.scripts.ScriptEntryData;
import net.aufdemrand.denizencore.scripts.ScriptRegistry;
import net.aufdemrand.denizencore.scripts.commands.core.DetermineCommand;
import net.aufdemrand.denizencore.scripts.containers.ScriptContainer;
import net.aufdemrand.denizencore.scripts.queues.ScriptQueue;
import net.aufdemrand.denizencore.scripts.queues.core.InstantQueue;
import net.aufdemrand.denizencore.utilities.YamlConfiguration;
import net.aufdemrand.denizencore.utilities.debugging.dB;
import net.aufdemrand.denizencore.utilities.text.StringHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class CustomScriptContainer extends ScriptContainer {

    // <--[language]
    // @name Custom Script Containers
    // @group Script Container System
    // @description
    // Custom script containers are used to define a template type for a custom object.
    //
    // Custom script containers have no required keys but several optional ones.
    // Use 'tags' key to define scripted tags,
    // 'mechanisms' to define scripted mechanisms,
    // 'inherit' to define what other custom script to inherit from,
    // and any other key name to define a default object field.
    //
    // <code>
    // Custom_Script_Name:
    //
    //   type: custom
    //
    //   # Use 'inherit' to make this custom script container inherit from another custom object.
    //   inherit: some_object
    //
    //   # This adds default field 'some_field' with initial value of 'some_value'.
    //   some_field: some_value
    //
    //   # List additional fields here...
    //
    //   # Use 'tags' to define scripted tags on the object.
    //   # Tags are subject to the same rules as procedure scripts:
    //   # NEVER change any external state. Just determine a value. Nothing else should change from the script.
    //   tags:
    //
    //     # This would be read like <def[my_object].my_tag>
    //     my_tag:
    //     # Perform any logic here, and 'determine' the result.
    //     - determine 3
    //
    //     # list additional tags here...
    //
    //   # Use 'mechanisms' to define scripted mechanisms on the object.
    //   # Note that these should only ever determine a new object,
    //   # with NO CHANGES AT ALL outside the replacement determined object.
    //   # (Same rules as tags and procedure scripts).
    //   mechanisms:
    //
    //     # This would be used like custom@Custom_Script_Name[my_mech=3]
    //     my_mech:
    //     - adjust <context.this> true_value:<context.value.mul[2]> save:new_val
    //     - determine <entry[new_val].result>
    //
    //     # list additional mechanisms here...
    //
    //
    // </code>
    //
    // -->

    public HashMap<String, String> defaultVars = new HashMap<>();

    public HashMap<String, dObject> getVars() {
        HashMap<String, dObject> vars;
        if (inherit != null) {
            ScriptContainer sc = ScriptRegistry.getScriptContainer(inherit);
            if (sc != null && sc instanceof CustomScriptContainer) {
                vars = ((CustomScriptContainer) sc).getVars();
            }
            else {
                vars = new HashMap<>();
            }
        }
        else {
            vars = new HashMap<>();
        }
        for (Map.Entry<String, String> str : defaultVars.entrySet()) {
            vars.put(str.getKey(), new Element(str.getValue()));
        }
        return vars;
    }

    public String inherit = null;

    public CustomScriptContainer(YamlConfiguration configurationSection, String scriptContainerName) {
        super(configurationSection, scriptContainerName);

        for (StringHolder str : getConfigurationSection("").getKeys(false)) {
            if (str.low.equals("inherit")) {
                inherit = getString(str.str);
            }
            else if (!(str.low.equals("type") || str.low.equals("tags") || str.low.equals("mechanisms")
                    || str.low.equals("speed") || str.low.equals("debug")
                    || configurationSection.getConfigurationSection(str.str) != null)) {
                defaultVars.put(str.low, getString(str.str));
            }
        }
    }

    public boolean hasPath(String path) {
        CustomScriptContainer csc = this;
        while (csc != null) {
            if (csc.contains(path)) {
                return true;
            }
            csc = ScriptRegistry.getScriptContainerAs(csc.inherit, CustomScriptContainer.class);
        }
        return false;
    }

    public long runTagScript(String path, dObject val, CustomObject obj, ScriptEntryData data) {
        CustomScriptContainer csc = this;
        while (csc != null) {
            if (csc.contains("tags." + path)) {
                dB.echoDebug(this, "[CustomObject] Calculating tag: " + path + " for " + csc.getName());
                ScriptQueue queue = new InstantQueue("TAG_" + csc.getName() + "_" + path + "__");
                List<ScriptEntry> listOfEntries = csc.getEntries(data, "tags." + path);
                long id = DetermineCommand.getNewId();
                ScriptBuilder.addObjectToEntries(listOfEntries, "reqid", id);
                CustomScriptContextSource cscs = new CustomScriptContextSource();
                cscs.obj = obj;
                cscs.value = val;
                queue.setContextSource(cscs);
                queue.addEntries(listOfEntries);
                queue.start();
                return id;
            }
            dB.echoDebug(this, "[CustomObject] Grabbing parent of " + csc.getName());
            csc = ScriptRegistry.getScriptContainerAs(csc.inherit, CustomScriptContainer.class);
        }
        dB.echoDebug(this, "Unable to find tag handler for " + path + " for " + this.getName());
        return -1;
    }

    public long runMechScript(String path, CustomObject obj, dObject value) {
        CustomScriptContainer csc = this;
        while (csc != null) {
            if (csc.contains("mechanisms." + path)) {
                ScriptQueue queue = new InstantQueue("MECH_" + csc.getName() + "_" + path + "__");
                List<ScriptEntry> listOfEntries = csc.getEntries(DenizenCore.getImplementation().getEmptyScriptEntryData(), "mechanisms." + path);
                long id = DetermineCommand.getNewId();
                ScriptBuilder.addObjectToEntries(listOfEntries, "reqid", id);
                CustomScriptContextSource cscs = new CustomScriptContextSource();
                cscs.obj = obj;
                cscs.value = value;
                queue.setContextSource(cscs);
                queue.addEntries(listOfEntries);
                queue.start();
                return id;
            }
            csc = ScriptRegistry.getScriptContainerAs(csc.inherit, CustomScriptContainer.class);
        }
        return -1;
    }

    public static class CustomScriptContextSource implements ContextSource {

        public CustomObject obj;

        public dObject value;

        @Override
        public boolean getShouldCache() {
            return true;
        }

        @Override
        public dObject getContext(String name) {
            if (name.equals("this")) {
                return obj;
            }
            else if (name.equals("value")) {
                return value;
            }
            else {
                return null;
            }
        }
    }
}
