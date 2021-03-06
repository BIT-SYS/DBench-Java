/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.oozie.workflow.lite;

import org.apache.commons.codec.binary.Base64;
import org.apache.hadoop.io.Writable;
import org.apache.oozie.action.hadoop.FsActionExecutor;
import org.apache.oozie.action.oozie.SubWorkflowActionExecutor;
import org.apache.oozie.service.ConfigurationService;
import org.apache.oozie.util.ELUtils;
import org.apache.oozie.util.IOUtils;
import org.apache.oozie.util.XConfiguration;
import org.apache.oozie.util.XmlUtils;
import org.apache.oozie.util.ParamChecker;
import org.apache.oozie.util.ParameterVerifier;
import org.apache.oozie.util.ParameterVerifierException;
import org.apache.oozie.util.WritableUtils;
import org.apache.oozie.ErrorCode;
import org.apache.oozie.workflow.WorkflowException;
import org.apache.oozie.action.ActionExecutor;
import org.apache.oozie.service.Services;
import org.apache.oozie.service.ActionService;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.jdom.Element;
import org.jdom.JDOMException;
import org.jdom.Namespace;
import org.xml.sax.SAXException;

import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.Validator;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.zip.*;

/**
 * Class to parse and validate workflow xml
 */
public class LiteWorkflowAppParser {

    private static final String DECISION_E = "decision";
    private static final String ACTION_E = "action";
    private static final String END_E = "end";
    private static final String START_E = "start";
    private static final String JOIN_E = "join";
    private static final String FORK_E = "fork";
    private static final Object KILL_E = "kill";

    private static final String SLA_INFO = "info";
    private static final String CREDENTIALS = "credentials";
    private static final String GLOBAL = "global";
    private static final String PARAMETERS = "parameters";

    private static final String NAME_A = "name";
    private static final String CRED_A = "cred";
    private static final String USER_RETRY_MAX_A = "retry-max";
    private static final String USER_RETRY_INTERVAL_A = "retry-interval";
    private static final String TO_A = "to";

    private static final String FORK_PATH_E = "path";
    private static final String FORK_START_A = "start";

    private static final String ACTION_OK_E = "ok";
    private static final String ACTION_ERROR_E = "error";

    private static final String DECISION_SWITCH_E = "switch";
    private static final String DECISION_CASE_E = "case";
    private static final String DECISION_DEFAULT_E = "default";

    private static final String KILL_MESSAGE_E = "message";
    public static final String VALIDATE_FORK_JOIN = "oozie.validate.ForkJoin";
    public static final String WF_VALIDATE_FORK_JOIN = "oozie.wf.validate.ForkJoin";

    public static final String DEFAULT_NAME_NODE = "oozie.actions.default.name-node";
    public static final String DEFAULT_JOB_TRACKER = "oozie.actions.default.job-tracker";
    public static final String OOZIE_GLOBAL = "oozie.wf.globalconf";

    private static final String JOB_TRACKER = "job-tracker";
    private static final String NAME_NODE = "name-node";
    private static final String JOB_XML = "job-xml";
    private static final String CONFIGURATION = "configuration";

    private Schema schema;
    private Class<? extends ControlNodeHandler> controlNodeHandler;
    private Class<? extends DecisionNodeHandler> decisionHandlerClass;
    private Class<? extends ActionNodeHandler> actionHandlerClass;

    private static enum VisitStatus {
        VISITING, VISITED
    }

    /**
     * We use this to store a node name and its top (eldest) decision parent node name for the forkjoin validation
     */
    class NodeAndTopDecisionParent {
        String node;
        String topDecisionParent;

        public NodeAndTopDecisionParent(String node, String topDecisionParent) {
            this.node = node;
            this.topDecisionParent = topDecisionParent;
        }
    }

    private List<String> forkList = new ArrayList<String>();
    private List<String> joinList = new ArrayList<String>();
    private StartNodeDef startNode;
    private List<NodeAndTopDecisionParent> visitedOkNodes = new ArrayList<NodeAndTopDecisionParent>();
    private List<String> visitedJoinNodes = new ArrayList<String>();

    private String defaultNameNode;
    private String defaultJobTracker;

    public LiteWorkflowAppParser(Schema schema,
                                 Class<? extends ControlNodeHandler> controlNodeHandler,
                                 Class<? extends DecisionNodeHandler> decisionHandlerClass,
                                 Class<? extends ActionNodeHandler> actionHandlerClass) throws WorkflowException {
        this.schema = schema;
        this.controlNodeHandler = controlNodeHandler;
        this.decisionHandlerClass = decisionHandlerClass;
        this.actionHandlerClass = actionHandlerClass;

        defaultNameNode = ConfigurationService.get(DEFAULT_NAME_NODE);
        if (defaultNameNode != null) {
            defaultNameNode = defaultNameNode.trim();
            if (defaultNameNode.isEmpty()) {
                defaultNameNode = null;
            }
        }
        defaultJobTracker = ConfigurationService.get(DEFAULT_JOB_TRACKER);
        if (defaultJobTracker != null) {
            defaultJobTracker = defaultJobTracker.trim();
            if (defaultJobTracker.isEmpty()) {
                defaultJobTracker = null;
            }
        }
    }

    public LiteWorkflowApp validateAndParse(Reader reader, Configuration jobConf) throws WorkflowException {
        return validateAndParse(reader, jobConf, null);
    }

    /**
     * Parse and validate xml to {@link LiteWorkflowApp}
     *
     * @param reader
     * @return LiteWorkflowApp
     * @throws WorkflowException
     */
    public LiteWorkflowApp validateAndParse(Reader reader, Configuration jobConf, Configuration configDefault)
            throws WorkflowException {
        try {
            StringWriter writer = new StringWriter();
            IOUtils.copyCharStream(reader, writer);
            String strDef = writer.toString();

            if (schema != null) {
                Validator validator = schema.newValidator();
                validator.validate(new StreamSource(new StringReader(strDef)));
            }

            Element wfDefElement = XmlUtils.parseXml(strDef);
            ParameterVerifier.verifyParameters(jobConf, wfDefElement);
            LiteWorkflowApp app = parse(strDef, wfDefElement, configDefault, jobConf);
            Map<String, VisitStatus> traversed = new HashMap<String, VisitStatus>();
            traversed.put(app.getNode(StartNodeDef.START).getName(), VisitStatus.VISITING);
            validate(app, app.getNode(StartNodeDef.START), traversed);
            //Validate whether fork/join are in pair or not
            if (jobConf.getBoolean(WF_VALIDATE_FORK_JOIN, true)
                    && ConfigurationService.getBoolean(VALIDATE_FORK_JOIN)) {
                validateForkJoin(app);
            }
            return app;
        }
        catch (ParameterVerifierException ex) {
            throw new WorkflowException(ex);
        }
        catch (JDOMException ex) {
            throw new WorkflowException(ErrorCode.E0700, ex.getMessage(), ex);
        }
        catch (SAXException ex) {
            throw new WorkflowException(ErrorCode.E0701, ex.getMessage(), ex);
        }
        catch (IOException ex) {
            throw new WorkflowException(ErrorCode.E0702, ex.getMessage(), ex);
        }
    }

    /**
     * Validate whether fork/join are in pair or not
     * @param app LiteWorkflowApp
     * @throws WorkflowException
     */
    private void validateForkJoin(LiteWorkflowApp app) throws WorkflowException {
        // Make sure the number of forks and joins in wf are equal
        if (forkList.size() != joinList.size()) {
            throw new WorkflowException(ErrorCode.E0730);
        }

        // No need to bother going through all of this if there are no fork/join nodes
        if (!forkList.isEmpty()) {
            visitedOkNodes.clear();
            visitedJoinNodes.clear();
            validateForkJoin(startNode, app, new LinkedList<String>(), new LinkedList<String>(), new LinkedList<String>(), true,
                    null);
        }
    }

    /*
     * Recursively walk through the DAG and make sure that all fork paths are valid.
     * This should be called from validateForkJoin(LiteWorkflowApp app).  It assumes that visitedOkNodes and visitedJoinNodes are
     * both empty ArrayLists on the first call.
     *
     * @param node the current node; use the startNode on the first call
     * @param app the WorkflowApp
     * @param forkNodes a stack of the current fork nodes
     * @param joinNodes a stack of the current join nodes
     * @param path a stack of the current path
     * @param okTo false if node (or an ancestor of node) was gotten to via an "error to" transition or via a join node that has
     * already been visited at least once before
     * @param topDecisionParent The top (eldest) decision node along the path to this node, or null if there isn't one
     * @throws WorkflowException
     */
    private void validateForkJoin(NodeDef node, LiteWorkflowApp app, Deque<String> forkNodes, Deque<String> joinNodes,
            Deque<String> path, boolean okTo, String topDecisionParent) throws WorkflowException {
        if (path.contains(node.getName())) {
            // cycle
            throw new WorkflowException(ErrorCode.E0741, node.getName(), Arrays.toString(path.toArray()));
        }
        path.push(node.getName());

        // Make sure that we're not revisiting a node (that's not a Kill, Join, or End type) that's been visited before from an
        // "ok to" transition; if its from an "error to" transition, then its okay to visit it multiple times.  Also, because we
        // traverse through join nodes multiple times, we have to make sure not to throw an exception here when we're really just
        // re-walking the same execution path (this is why we need the visitedJoinNodes list used later)
        if (okTo && !(node instanceof KillNodeDef) && !(node instanceof JoinNodeDef) && !(node instanceof EndNodeDef)) {
            NodeAndTopDecisionParent natdp = findInVisitedOkNodes(node.getName());
            if (natdp != null) {
                // However, if we've visited the node and it's under a decision node, we may be seeing it again and it's only
                // illegal if that decision node is not the same as what we're seeing now (because during execution we only go
                // down one path of the decision node, so while we're seeing the node multiple times here, during runtime it will
                // only be executed once).  Also, this decision node should be the top (eldest) decision node.  As null indicates
                // that there isn't a decision node, when this happens they must both be null to be valid.  Here is a good example
                // to visualize a node ("actionX") that has three "ok to" paths to it, but should still be a valid workflow (it may
                // be easier to see if you draw it):
                    // decisionA --> {actionX, decisionB}
                    // decisionB --> {actionX, actionY}
                    // actionY   --> {actionX}
                // And, if we visit this node twice under the same decision node in an invalid way, the path cycle checking code
                // will catch it, so we don't have to worry about that here.
                if ((natdp.topDecisionParent == null && topDecisionParent == null)
                     || (natdp.topDecisionParent == null && topDecisionParent != null)
                     || (natdp.topDecisionParent != null && topDecisionParent == null)
                     || !natdp.topDecisionParent.equals(topDecisionParent)) {
                    // If we get here, then we've seen this node before from an "ok to" transition but they don't have the same
                    // decision node top parent, which means that this node will be executed twice, which is illegal
                    throw new WorkflowException(ErrorCode.E0743, node.getName());
                }
            }
            else {
                // If we haven't transitioned to this node before, add it and its top decision parent node
                visitedOkNodes.add(new NodeAndTopDecisionParent(node.getName(), topDecisionParent));
            }
        }

        if (node instanceof StartNodeDef) {
            String transition = node.getTransitions().get(0);   // start always has only 1 transition
            NodeDef tranNode = app.getNode(transition);
            validateForkJoin(tranNode, app, forkNodes, joinNodes, path, okTo, topDecisionParent);
        }
        else if (node instanceof ActionNodeDef) {
            String transition = node.getTransitions().get(0);   // "ok to" transition
            NodeDef tranNode = app.getNode(transition);
            validateForkJoin(tranNode, app, forkNodes, joinNodes, path, okTo, topDecisionParent);  // propogate okTo
            transition = node.getTransitions().get(1);          // "error to" transition
            tranNode = app.getNode(transition);
            validateForkJoin(tranNode, app, forkNodes, joinNodes, path, false, topDecisionParent); // use false
        }
        else if (node instanceof DecisionNodeDef) {
            for(String transition : (new HashSet<String>(node.getTransitions()))) {
                NodeDef tranNode = app.getNode(transition);
                // if there currently isn't a topDecisionParent (i.e. null), then use this node instead of propagating null
                String parentDecisionNode = topDecisionParent;
                if (parentDecisionNode == null) {
                    parentDecisionNode = node.getName();
                }
                validateForkJoin(tranNode, app, forkNodes, joinNodes, path, okTo, parentDecisionNode);
            }
        }
        else if (node instanceof ForkNodeDef) {
            forkNodes.push(node.getName());
            List<String> transitionsList = node.getTransitions();
            HashSet<String> transitionsSet = new HashSet<String>(transitionsList);
            // Check that a fork doesn't go to the same node more than once
            if (!transitionsList.isEmpty() && transitionsList.size() != transitionsSet.size()) {
                // Now we have to figure out which node is the problem and what type of node they are (join and kill are ok)
                for (int i = 0; i < transitionsList.size(); i++) {
                    String a = transitionsList.get(i);
                    NodeDef aNode = app.getNode(a);
                    if (!(aNode instanceof JoinNodeDef) && !(aNode instanceof KillNodeDef)) {
                        for (int k = i+1; k < transitionsList.size(); k++) {
                            String b = transitionsList.get(k);
                            if (a.equals(b)) {
                                throw new WorkflowException(ErrorCode.E0744, node.getName(), a);
                            }
                        }
                    }
                }
            }
            for(String transition : transitionsSet) {
                NodeDef tranNode = app.getNode(transition);
                validateForkJoin(tranNode, app, forkNodes, joinNodes, path, okTo, topDecisionParent);
            }
            forkNodes.pop();
            if (!joinNodes.isEmpty()) {
                joinNodes.pop();
            }
        }
        else if (node instanceof JoinNodeDef) {
            if (forkNodes.isEmpty()) {
                // no fork for join to match with
                throw new WorkflowException(ErrorCode.E0742, node.getName());
            }
            if (forkNodes.size() > joinNodes.size() && (joinNodes.isEmpty() || !joinNodes.peek().equals(node.getName()))) {
                joinNodes.push(node.getName());
            }
            if (!joinNodes.peek().equals(node.getName())) {
                // join doesn't match fork
                throw new WorkflowException(ErrorCode.E0732, forkNodes.peek(), node.getName(), joinNodes.peek());
            }
            joinNodes.pop();
            String currentForkNode = forkNodes.pop();
            String transition = node.getTransitions().get(0);   // join always has only 1 transition
            NodeDef tranNode = app.getNode(transition);
            // If we're already under a situation where okTo is false, use false (propogate it)
            // Or if we've already visited this join node, use false (because we've already traversed this path before and we don't
            // want to throw an exception from the check against visitedOkNodes)
            if (!okTo || visitedJoinNodes.contains(node.getName())) {
                validateForkJoin(tranNode, app, forkNodes, joinNodes, path, false, topDecisionParent);
            // Else, use true because this is either the first time we've gone through this join node or okTo was already false
            } else {
                visitedJoinNodes.add(node.getName());
                validateForkJoin(tranNode, app, forkNodes, joinNodes, path, true, topDecisionParent);
            }
            forkNodes.push(currentForkNode);
            joinNodes.push(node.getName());
        }
        else if (node instanceof KillNodeDef) {
            // do nothing
        }
        else if (node instanceof EndNodeDef) {
            if (!forkNodes.isEmpty()) {
                path.pop();     // = node
                String parent = path.peek();
                // can't go to an end node in a fork
                throw new WorkflowException(ErrorCode.E0737, parent, node.getName());
            }
        }
        else {
            // invalid node type (shouldn't happen)
            throw new WorkflowException(ErrorCode.E0740, node.getName());
        }
        path.pop();
    }

    /**
     * Return a {@link NodeAndTopDecisionParent} whose {@link NodeAndTopDecisionParent#node} is equal to the passed in name, or null
     * if it isn't in the {@link LiteWorkflowAppParser#visitedOkNodes} list.
     *
     * @param name The name to search for
     * @return a NodeAndTopDecisionParent or null
     */
    private NodeAndTopDecisionParent findInVisitedOkNodes(String name) {
        NodeAndTopDecisionParent natdp = null;
        for (NodeAndTopDecisionParent v : visitedOkNodes) {
            if (v.node.equals(name)) {
                natdp = v;
                break;
            }
        }
        return natdp;
    }

    /**
     * Parse xml to {@link LiteWorkflowApp}
     *
     * @param strDef
     * @param root
     * @param configDefault
     * @param jobConf
     * @return LiteWorkflowApp
     * @throws WorkflowException
     */
    @SuppressWarnings({"unchecked"})
    private LiteWorkflowApp parse(String strDef, Element root, Configuration configDefault, Configuration jobConf)
            throws WorkflowException {
        Namespace ns = root.getNamespace();
        LiteWorkflowApp def = null;
        GlobalSectionData gData = jobConf.get(OOZIE_GLOBAL) == null ?
                null : getGlobalFromString(jobConf.get(OOZIE_GLOBAL));
        boolean serializedGlobalConf = false;
        for (Element eNode : (List<Element>) root.getChildren()) {
            if (eNode.getName().equals(START_E)) {
                def = new LiteWorkflowApp(root.getAttributeValue(NAME_A), strDef,
                                          new StartNodeDef(controlNodeHandler, eNode.getAttributeValue(TO_A)));
            } else if (eNode.getName().equals(END_E)) {
                def.addNode(new EndNodeDef(eNode.getAttributeValue(NAME_A), controlNodeHandler));
            } else if (eNode.getName().equals(KILL_E)) {
                def.addNode(new KillNodeDef(eNode.getAttributeValue(NAME_A),
                                            eNode.getChildText(KILL_MESSAGE_E, ns), controlNodeHandler));
            } else if (eNode.getName().equals(FORK_E)) {
                List<String> paths = new ArrayList<String>();
                for (Element tran : (List<Element>) eNode.getChildren(FORK_PATH_E, ns)) {
                    paths.add(tran.getAttributeValue(FORK_START_A));
                }
                def.addNode(new ForkNodeDef(eNode.getAttributeValue(NAME_A), controlNodeHandler, paths));
            } else if (eNode.getName().equals(JOIN_E)) {
                def.addNode(new JoinNodeDef(eNode.getAttributeValue(NAME_A), controlNodeHandler, eNode.getAttributeValue(TO_A)));
            } else if (eNode.getName().equals(DECISION_E)) {
                Element eSwitch = eNode.getChild(DECISION_SWITCH_E, ns);
                List<String> transitions = new ArrayList<String>();
                for (Element e : (List<Element>) eSwitch.getChildren(DECISION_CASE_E, ns)) {
                    transitions.add(e.getAttributeValue(TO_A));
                }
                transitions.add(eSwitch.getChild(DECISION_DEFAULT_E, ns).getAttributeValue(TO_A));

                String switchStatement = XmlUtils.prettyPrint(eSwitch).toString();
                def.addNode(new DecisionNodeDef(eNode.getAttributeValue(NAME_A), switchStatement, decisionHandlerClass,
                                                transitions));
            } else if (ACTION_E.equals(eNode.getName())) {
                String[] transitions = new String[2];
                Element eActionConf = null;
                for (Element elem : (List<Element>) eNode.getChildren()) {
                    if (ACTION_OK_E.equals(elem.getName())) {
                        transitions[0] = elem.getAttributeValue(TO_A);
                    } else if (ACTION_ERROR_E.equals(elem.getName())) {
                        transitions[1] = elem.getAttributeValue(TO_A);
                    } else if (SLA_INFO.equals(elem.getName()) || CREDENTIALS.equals(elem.getName())) {
                        continue;
                    } else {
                        if (!serializedGlobalConf  && elem.getName().equals(SubWorkflowActionExecutor.ACTION_TYPE) &&
                                elem.getChild(("propagate-configuration"), ns) != null) {
                            serializedGlobalConf = true;
                            jobConf.set(OOZIE_GLOBAL, getGlobalString(gData));
                        }
                        eActionConf = elem;
                        handleDefaultsAndGlobal(gData, configDefault, elem);
                    }
                }

                String credStr = eNode.getAttributeValue(CRED_A);
                String userRetryMaxStr = eNode.getAttributeValue(USER_RETRY_MAX_A);
                String userRetryIntervalStr = eNode.getAttributeValue(USER_RETRY_INTERVAL_A);
                try {
                    if (!StringUtils.isEmpty(userRetryMaxStr)) {
                        userRetryMaxStr = ELUtils.resolveAppName(userRetryMaxStr, jobConf);
                    }
                    if (!StringUtils.isEmpty(userRetryIntervalStr)) {
                        userRetryIntervalStr = ELUtils.resolveAppName(userRetryIntervalStr, jobConf);
                    }
                }
                catch (Exception e) {
                    throw new WorkflowException(ErrorCode.E0703, e.getMessage());
                }

                String actionConf = XmlUtils.prettyPrint(eActionConf).toString();
                def.addNode(new ActionNodeDef(eNode.getAttributeValue(NAME_A), actionConf, actionHandlerClass,
                                              transitions[0], transitions[1], credStr,
                                              userRetryMaxStr, userRetryIntervalStr));
            } else if (SLA_INFO.equals(eNode.getName()) || CREDENTIALS.equals(eNode.getName())) {
                // No operation is required
            } else if (eNode.getName().equals(GLOBAL)) {
                if(jobConf.get(OOZIE_GLOBAL) != null) {
                    gData = getGlobalFromString(jobConf.get(OOZIE_GLOBAL));
                    handleDefaultsAndGlobal(gData, null, eNode);
                }
                gData = parseGlobalSection(ns, eNode);
            } else if (eNode.getName().equals(PARAMETERS)) {
                // No operation is required
            } else {
                throw new WorkflowException(ErrorCode.E0703, eNode.getName());
            }
        }
        return def;
    }

    /**
     * Read the GlobalSectionData from Base64 string.
     * @param globalStr
     * @return GlobalSectionData
     * @throws WorkflowException
     */
    private GlobalSectionData getGlobalFromString(String globalStr) throws WorkflowException {
        GlobalSectionData globalSectionData = new GlobalSectionData();
        try {
            byte[] data = Base64.decodeBase64(globalStr);
            Inflater inflater = new Inflater();
            DataInputStream ois = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data), inflater));
            globalSectionData.readFields(ois);
            ois.close();
        } catch (Exception ex) {
            throw new WorkflowException(ErrorCode.E0700, "Error while processing global section conf");
        }
        return globalSectionData;
    }


    /**
     * Write the GlobalSectionData to a Base64 string.
     * @param globalSectionData
     * @return String
     * @throws WorkflowException
     */
    private String getGlobalString(GlobalSectionData globalSectionData) throws WorkflowException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream oos = null;
        try {
            Deflater def = new Deflater();
            oos = new DataOutputStream(new DeflaterOutputStream(baos, def));
            globalSectionData.write(oos);
            oos.close();
        } catch (IOException e) {
            throw new WorkflowException(ErrorCode.E0700, "Error while processing global section conf");
        }
        return Base64.encodeBase64String(baos.toByteArray());
    }

    /**
     * Validate workflow xml
     *
     * @param app
     * @param node
     * @param traversed
     * @throws WorkflowException
     */
    private void validate(LiteWorkflowApp app, NodeDef node, Map<String, VisitStatus> traversed) throws WorkflowException {
        if (node instanceof StartNodeDef) {
            startNode = (StartNodeDef) node;
        }
        else {
            try {
                ParamChecker.validateActionName(node.getName());
            }
            catch (IllegalArgumentException ex) {
                throw new WorkflowException(ErrorCode.E0724, ex.getMessage());
            }
        }
        if (node instanceof ActionNodeDef) {
            try {
                Element action = XmlUtils.parseXml(node.getConf());
                boolean supportedAction = Services.get().get(ActionService.class).getExecutor(action.getName()) != null;
                if (!supportedAction) {
                    throw new WorkflowException(ErrorCode.E0723, node.getName(), action.getName());
                }
            }
            catch (JDOMException ex) {
                throw new RuntimeException("It should never happen, " + ex.getMessage(), ex);
            }
        }

        if(node instanceof ForkNodeDef){
            forkList.add(node.getName());
        }

        if(node instanceof JoinNodeDef){
            joinList.add(node.getName());
        }

        if (node instanceof EndNodeDef) {
            traversed.put(node.getName(), VisitStatus.VISITED);
            return;
        }
        if (node instanceof KillNodeDef) {
            traversed.put(node.getName(), VisitStatus.VISITED);
            return;
        }
        for (String transition : node.getTransitions()) {

            if (app.getNode(transition) == null) {
                throw new WorkflowException(ErrorCode.E0708, node.getName(), transition);
            }

            //check if it is a cycle
            if (traversed.get(app.getNode(transition).getName()) == VisitStatus.VISITING) {
                throw new WorkflowException(ErrorCode.E0707, app.getNode(transition).getName());
            }
            //ignore validated one
            if (traversed.get(app.getNode(transition).getName()) == VisitStatus.VISITED) {
                continue;
            }

            traversed.put(app.getNode(transition).getName(), VisitStatus.VISITING);
            validate(app, app.getNode(transition), traversed);
        }
        traversed.put(node.getName(), VisitStatus.VISITED);
    }

    private void addChildElement(Element parent, Namespace ns, String childName, String childValue) {
        Element child = new Element(childName, ns);
        child.setText(childValue);
        parent.addContent(child);
    }

    private class GlobalSectionData implements Writable {
        String jobTracker;
        String nameNode;
        List<String> jobXmls;
        Configuration conf;

        public GlobalSectionData() {
        }

        public GlobalSectionData(String jobTracker, String nameNode, List<String> jobXmls, Configuration conf) {
            this.jobTracker = jobTracker;
            this.nameNode = nameNode;
            this.jobXmls = jobXmls;
            this.conf = conf;
        }

        @Override
        public void write(DataOutput dataOutput) throws IOException {
            WritableUtils.writeStr(dataOutput, jobTracker);
            WritableUtils.writeStr(dataOutput, nameNode);

            if(jobXmls != null && !jobXmls.isEmpty()) {
                dataOutput.writeInt(jobXmls.size());
                for (String content : jobXmls) {
                    WritableUtils.writeStr(dataOutput, content);
                }
            } else {
                dataOutput.writeInt(0);
            }
            if(conf != null) {
                WritableUtils.writeStr(dataOutput, XmlUtils.prettyPrint(conf).toString());
            } else {
                WritableUtils.writeStr(dataOutput, null);
            }
        }

        @Override
        public void readFields(DataInput dataInput) throws IOException {
            jobTracker = WritableUtils.readStr(dataInput);
            nameNode = WritableUtils.readStr(dataInput);
            int length = dataInput.readInt();
            if (length > 0) {
                jobXmls = new ArrayList<String>();
                for (int i = 0; i < length; i++) {
                    jobXmls.add(WritableUtils.readStr(dataInput));
                }
            }
            String confString = WritableUtils.readStr(dataInput);
            if(confString != null) {
                conf = new XConfiguration(new StringReader(confString));
            }
        }
    }

    private GlobalSectionData parseGlobalSection(Namespace ns, Element global) throws WorkflowException {
        GlobalSectionData gData = null;
        if (global != null) {
            String globalJobTracker = null;
            Element globalJobTrackerElement = global.getChild(JOB_TRACKER, ns);
            if (globalJobTrackerElement != null) {
                globalJobTracker = globalJobTrackerElement.getValue();
            }

            String globalNameNode = null;
            Element globalNameNodeElement = global.getChild(NAME_NODE, ns);
            if (globalNameNodeElement != null) {
                globalNameNode = globalNameNodeElement.getValue();
            }

            List<String> globalJobXmls = null;
            @SuppressWarnings("unchecked")
            List<Element> globalJobXmlElements = global.getChildren(JOB_XML, ns);
            if (!globalJobXmlElements.isEmpty()) {
                globalJobXmls = new ArrayList<String>(globalJobXmlElements.size());
                for(Element jobXmlElement: globalJobXmlElements) {
                    globalJobXmls.add(jobXmlElement.getText());
                }
            }

            Configuration globalConf = null;
            Element globalConfigurationElement = global.getChild(CONFIGURATION, ns);
            if (globalConfigurationElement != null) {
                try {
                    globalConf = new XConfiguration(new StringReader(XmlUtils.prettyPrint(globalConfigurationElement).toString()));
                } catch (IOException ioe) {
                    throw new WorkflowException(ErrorCode.E0700, "Error while processing global section conf");
                }
            }
            gData = new GlobalSectionData(globalJobTracker, globalNameNode, globalJobXmls, globalConf);
        }
        return gData;
    }

    private void handleDefaultsAndGlobal(GlobalSectionData gData, Configuration configDefault, Element actionElement)
            throws WorkflowException {

        ActionExecutor ae = Services.get().get(ActionService.class).getExecutor(actionElement.getName());
        if (ae == null && !GLOBAL.equals(actionElement.getName())) {
            throw new WorkflowException(ErrorCode.E0723, actionElement.getName(), ActionService.class.getName());
        }

        Namespace actionNs = actionElement.getNamespace();

        // If this is the global section or ActionExecutor.requiresNameNodeJobTracker() returns true, we parse the action's
        // <name-node> and <job-tracker> fields.  If those aren't defined, we take them from the <global> section.  If those
        // aren't defined, we take them from the oozie-site defaults.  If those aren't defined, we throw a WorkflowException.
        // However, for the SubWorkflow and FS Actions, as well as the <global> section, we don't throw the WorkflowException.
        // Also, we only parse the NN (not the JT) for the FS Action.
        if (SubWorkflowActionExecutor.ACTION_TYPE.equals(actionElement.getName()) ||
                FsActionExecutor.ACTION_TYPE.equals(actionElement.getName()) ||
                GLOBAL.equals(actionElement.getName()) || ae.requiresNameNodeJobTracker()) {
            if (actionElement.getChild(NAME_NODE, actionNs) == null) {
                if (gData != null && gData.nameNode != null) {
                    addChildElement(actionElement, actionNs, NAME_NODE, gData.nameNode);
                } else if (defaultNameNode != null) {
                    addChildElement(actionElement, actionNs, NAME_NODE, defaultNameNode);
                } else if (!(SubWorkflowActionExecutor.ACTION_TYPE.equals(actionElement.getName()) ||
                        FsActionExecutor.ACTION_TYPE.equals(actionElement.getName()) ||
                        GLOBAL.equals(actionElement.getName()))) {
                    throw new WorkflowException(ErrorCode.E0701, "No " + NAME_NODE + " defined");
                }
            }
            if (actionElement.getChild(JOB_TRACKER, actionNs) == null &&
                    !FsActionExecutor.ACTION_TYPE.equals(actionElement.getName())) {
                if (gData != null && gData.jobTracker != null) {
                    addChildElement(actionElement, actionNs, JOB_TRACKER, gData.jobTracker);
                } else if (defaultJobTracker != null) {
                    addChildElement(actionElement, actionNs, JOB_TRACKER, defaultJobTracker);
                } else if (!(SubWorkflowActionExecutor.ACTION_TYPE.equals(actionElement.getName()) ||
                        GLOBAL.equals(actionElement.getName()))) {
                    throw new WorkflowException(ErrorCode.E0701, "No " + JOB_TRACKER + " defined");
                }
            }
        }

        // If this is the global section or ActionExecutor.supportsConfigurationJobXML() returns true, we parse the action's
        // <configuration> and <job-xml> fields.  We also merge this with those from the <global> section, if given.  If none are
        // defined, empty values are placed.  Exceptions are thrown if there's an error parsing, but not if they're not given.
        if ( GLOBAL.equals(actionElement.getName()) || ae.supportsConfigurationJobXML()) {
            @SuppressWarnings("unchecked")
            List<Element> actionJobXmls = actionElement.getChildren(JOB_XML, actionNs);
            if (gData != null && gData.jobXmls != null) {
                for(String gJobXml : gData.jobXmls) {
                    boolean alreadyExists = false;
                    for (Element actionXml : actionJobXmls) {
                        if (gJobXml.equals(actionXml.getText())) {
                            alreadyExists = true;
                            break;
                        }
                    }
                    if (!alreadyExists) {
                        Element ejobXml = new Element(JOB_XML, actionNs);
                        ejobXml.setText(gJobXml);
                        actionElement.addContent(ejobXml);
                    }
                }
            }

            try {
                XConfiguration actionConf = new XConfiguration();
                if (configDefault != null)
                    XConfiguration.copy(configDefault, actionConf);
                if (gData != null && gData.conf != null) {
                    XConfiguration.copy(gData.conf, actionConf);
                }
                Element actionConfiguration = actionElement.getChild(CONFIGURATION, actionNs);
                if (actionConfiguration != null) {
                    //copy and override
                    XConfiguration.copy(new XConfiguration(new StringReader(XmlUtils.prettyPrint(actionConfiguration).toString())),
                            actionConf);
                }
                int position = actionElement.indexOf(actionConfiguration);
                actionElement.removeContent(actionConfiguration); //replace with enhanced one
                Element eConfXml = XmlUtils.parseXml(actionConf.toXmlString(false));
                eConfXml.detach();
                eConfXml.setNamespace(actionNs);
                if (position > 0) {
                    actionElement.addContent(position, eConfXml);
                }
                else {
                    actionElement.addContent(eConfXml);
                }
            }
            catch (IOException e) {
                throw new WorkflowException(ErrorCode.E0700, "Error while processing action conf");
            }
            catch (JDOMException e) {
                throw new WorkflowException(ErrorCode.E0700, "Error while processing action conf");
            }
        }
    }
}