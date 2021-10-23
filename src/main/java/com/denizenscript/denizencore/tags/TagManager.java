package com.denizenscript.denizencore.tags;

import com.denizenscript.denizencore.exceptions.TagProcessingException;
import com.denizenscript.denizencore.objects.*;
import com.denizenscript.denizencore.objects.core.ElementTag;
import com.denizenscript.denizencore.scripts.ScriptEntry;
import com.denizenscript.denizencore.tags.core.*;
import com.denizenscript.denizencore.utilities.CoreUtilities;
import com.denizenscript.denizencore.utilities.codegen.TagCodeGenerator;
import com.denizenscript.denizencore.utilities.codegen.TagNamer;
import com.denizenscript.denizencore.utilities.debugging.Debug;
import com.denizenscript.denizencore.DenizenCore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class TagManager {

    public static void registerCoreTags() {
        // Objects
        new CustomTagBase();
        new DurationTagBase();
        new ElementTagBase();
        new ListTagBase();
        new MapTagBase();
        new QueueTagBase();
        new ScriptTagBase();
        new TimeTagBase();

        // Utilities
        new ContextTagBase();
        new DefinitionTagBase();
        new EscapeTagBase();
        new ListSingleTagBase();
        new ProcedureScriptTagBase();
        new TernaryTagBase();
        new UtilTagBase();
    }

    public static class TagBaseData {

        public String name;

        public TagRunnable.RootForm rootForm;

        public TagRunnable.BaseInterface<?> baseForm;

        public Class<? extends ObjectTag> returnType;

        public ObjectTagProcessor<? extends ObjectTag> processor;

        public TagBaseData() {
        }

        public <R extends ObjectTag> TagBaseData(String name, Class<R> returnType, TagRunnable.BaseInterface<R> baseForm) {
            this.name = name;
            this.returnType = returnType;
            this.baseForm = baseForm;
            ObjectFetcher.ObjectType type = ObjectFetcher.objectsByClass.get(returnType);
            processor = type == null ? null : type.tagProcessor;
        }
    }

    public static HashMap<String, TagBaseData> baseTags = new HashMap<>();

    public static <R extends ObjectTag> void registerTagHandler(Class<R> returnType, String name, TagRunnable.BaseInterface<R> run) {
        baseTags.put(name, new TagBaseData(name, returnType, TagNamer.nameBaseInterface(name, run)));
    }

    @Deprecated
    public static void registerTagHandler(TagRunnable.RootForm run, String... names) {
        for (String name : names) {
            TagBaseData root = new TagBaseData();
            root.name = name;
            root.rootForm = run;
            baseTags.put(name, root);
        }
    }

    public static void fireEvent(ReplaceableTagEvent event) {
        if (Debug.verbose) {
            Debug.log("Tag fire: " + event.raw_tag + ", " + event.getAttributes().attributes[0].rawKey.contains("@") + ", " + event.hasAlternative() + "...");
        }
        TagBaseData baseHandler = event.alternateBase != null ? event.alternateBase : event.mainRef.tagBase;
        if (baseHandler != null) {
            Attribute attribute = event.getAttributes();
            try {
                if (event.mainRef.compiledStart != null && event.alternateBase == null) {
                    ObjectTag result = event.mainRef.compiledStart.run(attribute);
                    if (result != null) {
                        event.setReplacedObject(result.getObjectAttribute(attribute));
                        return;
                    }
                }
                else if (baseHandler.baseForm != null) {
                    ObjectTag result = baseHandler.baseForm.run(attribute);
                    if (result != null) {
                        event.setReplacedObject(result.getObjectAttribute(attribute.fulfill(1)));
                        return;
                    }
                }
                else if (baseHandler.rootForm != null) {
                    baseHandler.rootForm.run(event);
                    if (event.replaced()) {
                        return;
                    }
                }
            }
            catch (Throwable ex) {
                Debug.echoError(ex);
            }
            attribute.echoError("Tag-base '" + attribute.attributes[0].key + "' returned null.");
            return;
        }
        else {
            if (!event.hasAlternative()) {
                Debug.echoError("No tag-base handler for '" + event.getName() + "'.");
            }
        }
        if (Debug.verbose) {
            Debug.log("Tag unhandled!");
        }
    }

    public static boolean isInTag = false;

    public static void executeWithTimeLimit(final ReplaceableTagEvent event, int seconds) {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        Future<?> future = executor.submit(() -> {
            try {
                DenizenCore.getImplementation().preTagExecute();
                if (isInTag) {
                    fireEvent(event);
                }
                else {
                    isInTag = true;
                    fireEvent(event);
                    isInTag = false;
                }
            }
            finally {
                DenizenCore.getImplementation().postTagExecute();
            }
        });
        executor.shutdown();
        try {
            future.get(seconds, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Debug.echoError("Tag filling was interrupted!");
        }
        catch (ExecutionException e) {
            Debug.echoError(e);
        }
        catch (TimeoutException e) {
            future.cancel(true);
            Debug.echoError("Tag filling timed out!");
        }
        executor.shutdownNow();
    }

    public static String readSingleTag(String str, TagContext context) {
        try {
            ReplaceableTagEvent event = new ReplaceableTagEvent(str, context);
            return readSingleTagObject(context, event).toString();
        }
        catch (TagProcessingException ex) {
            Debug.echoError("Tag processing failed: " + ex.getMessage());
            return null;
        }
    }

    public static ObjectTag readSingleTagObject(ParseableTagPiece tag, TagContext context) {
        ReplaceableTagEvent event = new ReplaceableTagEvent(tag.tagData, tag.content, context);
        return readSingleTagObject(context, event);
    }

    public static boolean recentTagError = true;

    public static ObjectTag readSingleTagObjectNoDebug(TagContext context, ReplaceableTagEvent event) {
        int tT = DenizenCore.getImplementation().getTagTimeout();
        if (Debug.verbose) {
            Debug.log("Tag read: " + event.raw_tag + ", " + tT + "...");
        }
        if (tT <= 0 || isInTag || (!DenizenCore.getImplementation().shouldDebug(context) && !DenizenCore.getImplementation().tagTimeoutWhenSilent())) {
            fireEvent(event);
        }
        else {
            executeWithTimeLimit(event, tT);
        }
        if (!event.replaced() && event.hasAlternative()) {
            event.setReplacedObject(event.getAlternative());
        }
        return event.getReplacedObj();
    }

    public static ObjectTag readSingleTagObject(TagContext context, ReplaceableTagEvent event) {
        readSingleTagObjectNoDebug(context, event);
        if (context.debug && event.replaced()) {
            DenizenCore.getImplementation().debugTagFill(context, event.toString(), event.getReplacedObj().debuggable());
        }
        if (!event.replaced()) {
            String tagStr = "<LG><" + event.toString() + "<LG>><W>";
            Debug.echoError(context, "Tag " + tagStr + " is invalid!");
            recentTagError = true;
            if (OBJECTTAG_CONFUSION_PATTERN.matcher(tagStr).matches()) {
                Debug.echoError(context, "'ObjectTag' notation is for documentation purposes, and not to be used literally."
                    + " An actual object must be inserted instead. If confused, join our Discord at https://discord.gg/Q6pZGSR to ask for help!");
            }
            if (!event.hasAlternative()) {
                Attribute attribute = event.getAttributes();
                if (attribute.fulfilled < attribute.attributes.length) {
                    Debug.echoDebug(event.getScriptEntry(), "   Unfilled or unrecognized sub-tag(s) '<R>" + attribute.unfilledString() + "<W>' for tag <LG><" + attribute.origin + "<LG>><W>!");
                    if (attribute.lastValid != null) {
                        Debug.echoDebug(event.getScriptEntry(), "   The returned value from initial tag fragment '<LG>" + attribute.filledString() + "<W>' was: '<LG>" + attribute.lastValid.debuggable() + "<W>'.");
                    }
                    if (attribute.seemingSuccesses.size() > 0) {
                        String almost = attribute.seemingSuccesses.get(attribute.seemingSuccesses.size() - 1);
                        if (attribute.hasContextFailed) {
                            Debug.echoDebug(event.getScriptEntry(), "   Almost matched but failed (missing [context] parameter?): " + almost);
                        }
                        else {
                            Debug.echoDebug(event.getScriptEntry(), "   Almost matched but failed (possibly bad input?): " + almost);
                        }
                    }
                }
            }
            return new ElementTag(event.raw_tag);
        }
        return event.getReplacedObj();
    }

    public static Pattern OBJECTTAG_CONFUSION_PATTERN = Pattern.compile("<\\w+tag[\\[.>].*", Pattern.CASE_INSENSITIVE);

    public static HashMap<String, ParseableTag> preCalced = new HashMap<>();

    public static ParseableTag DEFAULT_PARSEABLE_EMPTY = new ParseableTag("");

    public static class ParseableTagPiece {

        public String content;

        public boolean isTag = false;

        public boolean isError = false;

        public ReplaceableTagEvent.ReferenceData tagData = null;

        @Override
        public String toString() {
            return "(" + isError + ", " + isTag + ", " + (isTag ? tagData.rawTag : "") + ", " + content + ")";
        }
    }

    public static ObjectTag parseChainObject(List<ParseableTagPiece> pieces, TagContext context) {
        if (Debug.verbose) {
            Debug.log("Tag parse chain: " + pieces + "...");
        }
        if (pieces.size() < 2) {
            if (pieces.isEmpty()) {
                return new ElementTag("", true);
            }
            ParseableTagPiece pzero = pieces.get(0);
            if (pzero.isError) {
                Debug.echoError(context, pzero.content);
            }
            else if (pzero.isTag) {
                return readSingleTagObject(pzero, context);
            }
            ElementTag result = new ElementTag(pieces.get(0).content);
            result.isRawInput = true;
            return result;
        }
        StringBuilder helpy = new StringBuilder();
        for (ParseableTagPiece p : pieces) {
            if (p.isError) {
                Debug.echoError(context, p.content);
            }
            else if (p.isTag) {
                helpy.append(readSingleTagObject(p, context).toString());
            }
            else {
                helpy.append(p.content);
            }
        }
        ElementTag result = new ElementTag(helpy.toString(), true);
        result.isRawInput = true;
        return result;
    }

    public static String tag(String arg, TagContext context) {
        return tagObject(arg, context).toString();
    }

    public static ParseableTag parseTextToTag(String arg, TagContext context) {
        if (arg == null) {
            return null;
        }
        ParseableTag preParsed = preCalced.get(arg);
        if (preParsed != null) {
            return preParsed;
        }
        List<ParseableTagPiece> pieces = new ArrayList<>(1);
        if (arg.indexOf('>') == -1 || arg.length() < 3) {
            ParseableTagPiece txt = new ParseableTagPiece();
            txt.content = arg;
            pieces.add(txt);
            ParseableTag result = new ParseableTag(arg);
            result.pieces = pieces;
            return result;
        }
        int[] positions = new int[2];
        positions[0] = -1;
        locateTag(arg, positions, 0);
        if (positions[0] == -1) {
            ParseableTagPiece txt = new ParseableTagPiece();
            txt.content = arg;
            pieces.add(txt);
            ParseableTag result = new ParseableTag(arg);
            result.pieces = pieces;
            return result;
        }
        String orig = arg;
        while (positions[0] != -1) {
            ParseableTagPiece preText = null;
            if (positions[0] > 0) {
                preText = new ParseableTagPiece();
                preText.content = arg.substring(0, positions[0]);
                pieces.add(preText);
            }
            String tagToProc = arg.substring(positions[0] + 1, positions[1]);
            ParseableTagPiece midTag = new ParseableTagPiece();
            midTag.content = tagToProc;
            midTag.isTag = true;
            try {
                midTag.tagData = new ReplaceableTagEvent(tagToProc, context).mainRef;
                if (!midTag.tagData.noGenerate && midTag.tagData.tagBase != null && midTag.tagData.tagBase.baseForm != null) {
                    midTag.tagData.noGenerate = true;
                    midTag.tagData.compiledStart = TagCodeGenerator.generatePartialTag(midTag.tagData);
                }
                pieces.add(midTag);
                if (Debug.verbose) {
                    Debug.log("Tag: " + (preText == null ? "<null>" : preText.content) + " ||| " + midTag.content);
                }
            }
            catch (TagProcessingException ex) {
                Debug.echoError(context, "(Initial detection) Tag processing failed: " + ex.getMessage());
                ParseableTagPiece errorNote = new ParseableTagPiece();
                errorNote.isError = true;
                errorNote.content = "Tag processing failed: " + ex.getMessage();
                pieces.add(errorNote);
            }
            arg = arg.substring(positions[1] + 1);
            locateTag(arg, positions, 0);
        }
        if (arg.indexOf('<') != -1 && !arg.contains(":<-")) {
            ParseableTagPiece errorNote = new ParseableTagPiece();
            errorNote.isError = true;
            errorNote.content = "Potential issue: inconsistent tag marks in command! (issue snippet: " + arg + "; from: " + orig + ")";
            pieces.add(errorNote);
        }
        if (arg.length() > 0) {
            ParseableTagPiece postText = new ParseableTagPiece();
            postText.content = arg;
            pieces.add(postText);
        }
        if (Debug.verbose) {
            Debug.log("Tag chainify complete: " + arg);
        }
        ParseableTag result = new ParseableTag();
        result.pieces = pieces;
        result.hasTag = true;
        if (pieces.size() == 1 && pieces.get(0).isTag) {
            result.singleTag = pieces.get(0);
        }
        return result;
    }

    public static ObjectTag tagObject(String arg, TagContext context) {
        return parseTextToTag(arg, context).parse(context);
    }

    private static void locateTag(String arg, int[] holder, int start) {
        int first = arg.indexOf('<', start);
        holder[0] = first;
        if (first == -1) {
            return;
        }
        int len = arg.length();
        // Handle "<-" for the flag command
        if (first + 1 < len && (arg.charAt(first + 1) == '-')) {
            locateTag(arg, holder, first + 1);
            return;
        }
        int bracks = 1;
        for (int i = first + 1; i < len; i++) {
            if (arg.charAt(i) == '<') {
                bracks++;
            }
            else if (arg.charAt(i) == '>') {
                bracks--;
                if (bracks == 0) {
                    holder[1] = i;
                    return;
                }
            }
        }
        holder[0] = -1;
    }

    public static void fillArgumentObjects(ScriptEntry.InternalArgument arg, Argument ahArg, TagContext context) {
        ahArg.lower_value = null;
        ahArg.unsetValue();
        if (arg.prefix != null) {
            if (arg.prefix.value.hasTag) {
                ahArg.prefix = arg.prefix.value.parse(context).toString();
                ahArg.lower_prefix = CoreUtilities.toLowerCase(ahArg.prefix);
            }
            if (arg.value.hasTag) {
                ahArg.object = arg.value.parse(context);
            }
        }
        else {
            ahArg.object = arg.value.parse(context);
            ahArg.prefix = null;
            ahArg.lower_prefix = null;
        }
    }
}
