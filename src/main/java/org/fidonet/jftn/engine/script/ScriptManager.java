package org.fidonet.jftn.engine.script;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by IntelliJ IDEA.
 * User: Vladimir Kravets
 * Date: 8/29/11
 * Time: 11:40 AM
 * To change this template use File | Settings | File Templates.
 */
public class ScriptManager {

    private ScriptEngineManager scriptEngineManager;
    private Map<String, Object> scriptVariables;

    public ScriptManager() {
        scriptEngineManager = new ScriptEngineManager();
        scriptVariables = new HashMap<String, Object>();
    }

    public ScriptEngine getJythonScriptEngine() throws Exception {
        ScriptEngine scriptEngine = scriptEngineManager.getEngineByExtension("py");
        if (scriptEngine == null) {
            throw new Exception("Jython script engine is not found");
        }
        return scriptEngine;
    }

    public void runScript(File script) throws Exception {
        InputStream inputStream = new FileInputStream(script);
        this.runScript(inputStream);
    }

    public void runScript(InputStream stream) throws Exception {
        InputStreamReader reader = new InputStreamReader(stream);
        ScriptEngine jythonEngine = getJythonScriptEngine();
        if (!scriptVariables.isEmpty()) {
            for (String name : scriptVariables.keySet()) {
                jythonEngine.put(name, scriptVariables.get(name));
            }
        }
        jythonEngine.eval(reader);
    }

    public void addScriptVar(String name, Object value) {
        if (name != null && value != null) {
            scriptVariables.put(name, value);
        } else {
            // TODO: Log warn
        }
    }

    public void removeScriptVar(String name, Object value) {
        if (name != null && value != null) {
            if (scriptVariables.get(name) != null) {
                scriptVariables.remove(name);
            } else {
                // TODO: Log warn
            }
        } else {
            // TODO: Log warn
        }
    }
}
