/*
 * Copyright 2018 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.core.command.runtime.pmml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.xml.bind.annotation.XmlAccessType;
import javax.xml.bind.annotation.XmlAccessorType;
import javax.xml.bind.annotation.XmlAttribute;
import javax.xml.bind.annotation.XmlElement;
import javax.xml.bind.annotation.XmlRootElement;

import org.drools.core.command.IdentifiableResult;
import org.drools.core.command.RequestContextImpl;
import org.drools.core.definitions.InternalKnowledgePackage;
import org.drools.core.definitions.rule.impl.RuleImpl;
import org.drools.core.impl.InternalKnowledgeBase;
import org.drools.core.ruleunit.RuleUnitDescription;
import org.drools.core.ruleunit.RuleUnitDescriptionRegistry;
import org.drools.core.runtime.impl.ExecutionResultImpl;
import org.kie.api.KieBase;
import org.kie.api.command.ExecutableCommand;
import org.kie.api.pmml.OutputFieldFactory;
import org.kie.api.pmml.PMML4Output;
import org.kie.api.pmml.PMML4Result;
import org.kie.api.pmml.PMMLRequestData;
import org.kie.api.runtime.Context;
import org.kie.api.runtime.rule.DataSource;
import org.kie.api.runtime.rule.RuleUnit;
import org.kie.api.runtime.rule.RuleUnitExecutor;
import org.kie.internal.command.RegistryContext;

@XmlRootElement(name="apply-pmml-model-command")
@XmlAccessorType(XmlAccessType.FIELD)
public class ApplyPmmlModelCommand implements ExecutableCommand<PMML4Result>, IdentifiableResult {
    private static final long serialVersionUID = 19630331;
    @XmlAttribute(name="outIdentifier")
    private String outIdentifier;
    @XmlAttribute(name="packageName")
    private String packageName;
    @XmlAttribute(name="hasMining")
    private Boolean hasMining;
    @XmlElement(name="requestData")
    private PMMLRequestData requestData;

    
    public ApplyPmmlModelCommand() {
        // Necessary for JAXB
        super();
    }
    
    public ApplyPmmlModelCommand(PMMLRequestData requestData) {
        initialize(requestData, null);
    }
    
    public ApplyPmmlModelCommand(PMMLRequestData requestData, Boolean hasMining) {
        initialize(requestData, hasMining);
    }

    private void initialize(PMMLRequestData requestData, Boolean hasMining) {
        this.requestData = requestData;
        this.hasMining = hasMining != null ? hasMining : Boolean.FALSE;
    }
    
    public PMMLRequestData getRequestData() {
        return requestData;
    }

    public void setRequestData(PMMLRequestData requestData) {
        this.requestData = requestData;
    }
    
    public String getPackageName() {
        return packageName;
    }

    public void setPackageName(String packageName) {
        this.packageName = packageName;
    }
    
    public Boolean getHasMining() {
        return hasMining;
    }

    public void setHasMining(Boolean hasMining) {
        this.hasMining = hasMining;
    }
    
    public boolean isMining() {
        if (hasMining == null || hasMining.booleanValue() == false) return false;
        return true;
    }

    @Override
    public String getOutIdentifier() {
        return outIdentifier;
    }

    @Override
    public void setOutIdentifier(String outIdentifier) {
        this.outIdentifier = outIdentifier;
    }
    
    private Class<? extends RuleUnit> getStartingRuleUnit(String startingRule, InternalKnowledgeBase ikb, List<String> possiblePackages) {
        RuleUnitDescriptionRegistry unitRegistry = ikb.getRuleUnitDescriptionRegistry();
        Map<String,InternalKnowledgePackage> pkgs = ikb.getPackagesMap();
        RuleImpl ruleImpl = null;
        for (String pkgName: possiblePackages) {
            if (pkgs.containsKey(pkgName)) {
                InternalKnowledgePackage pkg = pkgs.get(pkgName);
                ruleImpl = pkg.getRule(startingRule);
                if (ruleImpl != null) {
                    RuleUnitDescription descr = unitRegistry.getDescription(ruleImpl).orElse(null);
                    if (descr != null) {
                        return descr.getRuleUnitClass();
                    }
                }
            }
        }
        return null;
    }
    
    private List<String> calculatePossiblePackageNames(String modelId, String...knownPackageNames) {
        List<String> packageNames = new ArrayList<>();
        String javaModelId = modelId.replaceAll("\\s","");
        if (knownPackageNames != null && knownPackageNames.length > 0) {
            for (String knownPkgName: knownPackageNames) {
                packageNames.add(knownPkgName + "." + javaModelId);
            }
        }
        String basePkgName = PmmlConstants.DEFAULT_ROOT_PACKAGE+"."+javaModelId;
        packageNames.add(basePkgName);
        return packageNames;
    }

    
    
    @Override
    public PMML4Result execute(Context context) {
        RequestContextImpl ctx = (RequestContextImpl)context;
        if (requestData == null) {
            requestData = (PMMLRequestData)ctx.get("request");
        }
        if (packageName == null) {
            packageName = (String)ctx.get("packageName");
        }
        KieBase kbase = ((RegistryContext)context).lookup(KieBase.class);
        PMML4Result resultHolder = new PMML4Result(requestData.getCorrelationId());
        if (kbase == null) {
            resultHolder.setResultCode("ERROR-1");
        } else {
            boolean hasUnits = ((InternalKnowledgeBase)kbase).getRuleUnitDescriptionRegistry().hasUnits();
            if (!hasUnits) {
                resultHolder.setResultCode("ERROR-2");
            } else {
                RuleUnitExecutor executor = RuleUnitExecutor.create().bind(kbase);
                DataSource<PMMLRequestData> data = executor.newDataSource("request", this.requestData);
                DataSource<PMML4Result> resultData = executor.newDataSource("results", resultHolder);
                executor.newDataSource("pmmlData");
                if (isMining()) {
                    executor.newDataSource("childModelSegments");
                    executor.newDataSource("miningModelPojo");
                }
                
                data.insert(requestData);
                resultData.insert(resultHolder);
                String startingRule = isMining() ? "Start Mining - "+requestData.getModelName():"RuleUnitIndicator";
                List<String> possibleStartingPackages = calculatePossiblePackageNames(requestData.getModelName(), packageName);
                Class<? extends RuleUnit> ruleUnitClass= getStartingRuleUnit(startingRule, (InternalKnowledgeBase)kbase, possibleStartingPackages);
                executor.run(ruleUnitClass);
            }
        }
        List<PMML4Output<?>> outputs = OutputFieldFactory.createOutputsFromResults(resultHolder);
        ExecutionResultImpl execRes = ctx.lookup(ExecutionResultImpl.class);
        ctx.register(PMML4Result.class, resultHolder);
        execRes.setResult("results", resultHolder);
        outputs.forEach(out -> {
            execRes.setResult(out.getName(), out);
            resultHolder.updateResultVariable(out.getName(), out);
        });
        return resultHolder;
    }

    
}
