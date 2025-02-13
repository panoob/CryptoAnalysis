package crypto.constraints;

import boomerang.scene.DeclaredMethod;
import boomerang.scene.Statement;
import boomerang.scene.Type;
import boomerang.scene.Val;
import crypto.analysis.IAnalysisSeed;
import crypto.analysis.errors.CallToError;
import crypto.analysis.errors.HardCodedError;
import crypto.analysis.errors.InstanceOfError;
import crypto.analysis.errors.NeverTypeOfError;
import crypto.analysis.errors.NoCallToError;
import crypto.extractparameter.CallSiteWithExtractedValue;
import crypto.extractparameter.CallSiteWithParamIndex;
import crypto.extractparameter.ExtractedValue;
import crypto.rules.CrySLMethod;
import crypto.rules.CrySLObject;
import crypto.rules.CrySLPredicate;
import crypto.rules.ICrySLPredicateParameter;
import crypto.rules.ISLConstraint;
import crypto.utils.MatcherUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class PredicateConstraint extends EvaluableConstraint {

	protected PredicateConstraint(ISLConstraint origin, ConstraintSolver context) {
		super(origin, context);
	}

	@Override
	public void evaluate() {
		CrySLPredicate predicateConstraint = (CrySLPredicate) origin;
		String predName = predicateConstraint.getPredName();
		if (ConstraintSolver.predefinedPreds.contains(predName)) {
			handlePredefinedNames(predicateConstraint);
		}
	}

	private void handlePredefinedNames(CrySLPredicate predicate) {
		switch (predicate.getPredName()) {
			case "callTo":
                evaluateCallToPredicate(predicate.getParameters());
				break;
			case "noCallTo":
                evaluateNoCallToPredicate(predicate.getParameters());
				break;
			case "neverTypeOf":
				evaluateNeverTypeOfPredicate(predicate);
				break;
			case "length":
				// TODO Not implemented!
				break;
			case "notHardCoded":
				evaluateHardCodedPredicate(predicate);
				break;
			case "instanceOf":
				evaluateInstanceOfPredicate(predicate);
				break;
			default:
				LOGGER.error("Cannot evaluate predicate {}", predicate.getPredName());
        }
	}

	private void evaluateCallToPredicate(List<ICrySLPredicateParameter> callToMethods) {
		boolean isCalled = false;
		Collection<CrySLMethod> methods = parametersToCryslMethods(callToMethods);

		for (CrySLMethod predMethod : methods) {
			for (Statement statement : context.getCollectedCalls()) {
				if (!statement.containsInvokeExpr()) {
					continue;
				}

				DeclaredMethod foundCall = statement.getInvokeExpr().getMethod();
				Collection<CrySLMethod> matchingCryslMethods = MatcherUtils.getMatchingCryslMethodsToDeclaredMethod(context.getSpecification(), foundCall);
				if (matchingCryslMethods.contains(predMethod)) {
					isCalled = true;
				}
			}
		}

		if (!isCalled) {
			IAnalysisSeed seed = context.getObject();
			CallToError callToError = new CallToError(seed, context.getSpecification(), methods);
			errors.add(callToError);
		}
	}

	private void evaluateNoCallToPredicate(List<ICrySLPredicateParameter> noCallToMethods) {
		Collection<CrySLMethod> methods = parametersToCryslMethods(noCallToMethods);

		for (CrySLMethod predMethod : methods) {
			for (Statement statement : context.getCollectedCalls()) {
				if (!statement.containsInvokeExpr()) {
					continue;
				}

				DeclaredMethod foundCall = statement.getInvokeExpr().getMethod();
				if (MatcherUtils.matchCryslMethodAndDeclaredMethod(predMethod, foundCall)) {
					NoCallToError noCallToError = new NoCallToError(context.getObject(), statement, context.getSpecification());
					errors.add(noCallToError);
				}
			}
		}
	}

	private void evaluateNeverTypeOfPredicate(CrySLPredicate neverTypeOfPredicate) {
		List<CrySLObject> objects = parametersToCryslObjects(neverTypeOfPredicate.getParameters());

		if (objects.size() != 2) {
			return;
		}

		// neverTypeOf[$variable, $type]
		CrySLObject variable = objects.get(0);
		CrySLObject parameterType = objects.get(1);

		for (CallSiteWithParamIndex cs : context.getParameterAnalysisQuerySites()) {
			if (!variable.getName().equals(cs.getVarName())) {
				continue;
			}

			Collection<Type> types = context.getPropagatedTypes().get(cs);
			for (Type type : types) {
				if (!parameterType.getJavaType().equals(type.toString())) {
					continue;
				}

				ExtractedValue extractedValue = new ExtractedValue(cs.stmt(), cs.fact());
				CallSiteWithExtractedValue callSite = new CallSiteWithExtractedValue(cs, extractedValue);
				NeverTypeOfError neverTypeOfError = new NeverTypeOfError(context.getObject(), callSite, context.getSpecification(), neverTypeOfPredicate);
				errors.add(neverTypeOfError);
			}
		}
	}

	private void evaluateHardCodedPredicate(CrySLPredicate hardCodedPredicate) {
		List<CrySLObject> objects = parametersToCryslObjects(hardCodedPredicate.getParameters());

		if (objects.size() != 1) {
			return;
		}

		// notHardCoded[$variable]
		CrySLObject variable = objects.get(0);

		for (CallSiteWithParamIndex cs : context.getParsAndVals().keySet()) {
			if (!variable.getVarName().equals(cs.getVarName())) {
				continue;
			}

			Collection<ExtractedValue> extractedValues = context.getParsAndVals().get(cs);
			for (ExtractedValue extractedValue : extractedValues) {
				if (isHardCodedVariable(extractedValue) || isHardCodedArray(extractedValue)) {
					CallSiteWithExtractedValue callSiteWithExtractedValue = new CallSiteWithExtractedValue(cs, extractedValue);
					HardCodedError hardCodedError = new HardCodedError(context.getObject(), callSiteWithExtractedValue, context.getSpecification(), hardCodedPredicate);
					errors.add(hardCodedError);
				}
			}
		}
	}

	private void evaluateInstanceOfPredicate(CrySLPredicate instanceOfPredicate) {
		List<CrySLObject> objects = parametersToCryslObjects(instanceOfPredicate.getParameters());

		if (objects.size() != 2) {
			return;
		}

		// instanceOf[$variable, $type]
		CrySLObject variable = objects.get(0);
		CrySLObject parameterType = objects.get(1);

		for (CallSiteWithParamIndex cs : context.getParameterAnalysisQuerySites()) {
			if (!variable.getName().equals(cs.getVarName())) {
				continue;
			}

			boolean isSubType = false;
			Collection<Type> types = context.getPropagatedTypes().get(cs);
			for (Type type : types) {
				if (type.isNullType()) {
					continue;
				}

				if (type.isSubtypeOf(parameterType.getJavaType())) {
					isSubType = true;
				}
			}

			if (!isSubType) {
				ExtractedValue extractedValue = new ExtractedValue(cs.stmt(), cs.fact());
				CallSiteWithExtractedValue callSite = new CallSiteWithExtractedValue(cs, extractedValue);
				InstanceOfError instanceOfError = new InstanceOfError(context.getObject(), callSite, context.getSpecification(), instanceOfPredicate);
				errors.add(instanceOfError);
			}
		}
	}

	private Collection<CrySLMethod> parametersToCryslMethods(Collection<ICrySLPredicateParameter> parameters) {
		List<CrySLMethod> methods = new ArrayList<>();

		for (ICrySLPredicateParameter parameter : parameters) {
			if (!(parameter instanceof CrySLMethod)) {
				continue;
			}

			CrySLMethod crySLMethod = (CrySLMethod) parameter;
			methods.add(crySLMethod);
		}
		return methods;
	}

	private List<CrySLObject> parametersToCryslObjects(Collection<ICrySLPredicateParameter> parameters) {
		List<CrySLObject> objects = new ArrayList<>();

		for (ICrySLPredicateParameter parameter : parameters) {
			if (!(parameter instanceof CrySLObject)) {
				continue;
			}

			CrySLObject crySLObject = (CrySLObject) parameter;
			objects.add(crySLObject);
		}
		return objects;
	}

	private boolean isSubType(String subType, String superType) {
		boolean subTypes = subType.equals(superType);
		subTypes |= (subType + "[]").equals(superType);

		if (subTypes) {
			return true;
		}

        try {
            return Class.forName(superType).isAssignableFrom(Class.forName(subType));
        } catch (ClassNotFoundException e) {
            return false;
        }
	}

	public boolean isHardCodedVariable(ExtractedValue val) {
		// Check for basic constants
		if (val.getValue().isConstant()) {
			LOGGER.debug("Value {} is hard coded", val.getValue());
			return true;
		}

		// Objects initialized with 'new' are hard coded
		Statement statement = val.stmt();
		if (!statement.isAssign()) {
			LOGGER.debug("Value {} is not hard coded", val.getValue());
			return false;
		}

		Val rightOp = statement.getRightOp();
		if (rightOp.isNewExpr()) {
			LOGGER.debug("Value {} is hard coded", val.getValue());
			return true;
		}

		LOGGER.debug("Value {} is not hard coded", val.getValue());
		return false;
	}

	private boolean isHardCodedArray(ExtractedValue value) {
		Map<Integer, Val> extractedArray = extractArray(value);

		return !extractedArray.keySet().isEmpty();
	}
}
