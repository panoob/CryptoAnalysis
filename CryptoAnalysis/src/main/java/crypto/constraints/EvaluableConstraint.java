package crypto.constraints;

import boomerang.BackwardQuery;
import boomerang.Boomerang;
import boomerang.ForwardQuery;
import boomerang.results.BackwardBoomerangResults;
import boomerang.results.ForwardBoomerangResults;
import boomerang.scene.AllocVal;
import boomerang.scene.ControlFlowGraph;
import boomerang.scene.InvokeExpr;
import boomerang.scene.Method;
import boomerang.scene.Statement;
import boomerang.scene.Val;
import boomerang.scene.jimple.IntAndStringBoomerangOptions;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Table;
import crypto.analysis.errors.AbstractError;
import crypto.analysis.errors.ImpreciseValueExtractionError;
import crypto.extractparameter.CallSiteWithExtractedValue;
import crypto.extractparameter.CallSiteWithParamIndex;
import crypto.extractparameter.ExtractedValue;
import crypto.rules.CrySLComparisonConstraint;
import crypto.rules.CrySLConstraint;
import crypto.rules.CrySLExceptionConstraint;
import crypto.rules.CrySLPredicate;
import crypto.rules.CrySLValueConstraint;
import crypto.rules.ISLConstraint;
import crypto.utils.SootUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

public abstract class EvaluableConstraint {

	protected static final Logger LOGGER = LoggerFactory.getLogger(EvaluableConstraint.class);

	public static EvaluableConstraint getInstance(ISLConstraint con, ConstraintSolver context) {
		if (con instanceof CrySLComparisonConstraint) {
			return new ComparisonConstraint(con, context);
		} else if (con instanceof CrySLValueConstraint) {
			return new ValueConstraint(con, context);
		} else if (con instanceof CrySLPredicate) {
			return new PredicateConstraint(con, context);
		} else if (con instanceof CrySLConstraint) {
			return new BinaryConstraint((CrySLConstraint) con, context);
		} else if (con instanceof CrySLExceptionConstraint) {
			return new ExceptionConstraint((CrySLExceptionConstraint) con, context);
		}
		throw new RuntimeException("Type of constraint is not supported");
	}

	final Collection<AbstractError> errors = Sets.newHashSet();

	final ConstraintSolver context;

	final ISLConstraint origin;

	protected EvaluableConstraint(ISLConstraint origin, ConstraintSolver context) {
		this.origin = origin;
		this.context = context;
	}

	public abstract void evaluate();

	public boolean hasErrors() {
		return !errors.isEmpty();
	}

	protected Collection<AbstractError> getErrors() {
		return errors;
	}

	protected Map<String, CallSiteWithExtractedValue> extractValueAsString(String varName) {
		Map<String, CallSiteWithExtractedValue> varVal = Maps.newHashMap();
		for (CallSiteWithParamIndex wrappedCallSite : context.getParsAndVals().keySet()) {
			final Statement callSite = wrappedCallSite.stmt();

			for (ExtractedValue wrappedAllocSite : context.getParsAndVals().get(wrappedCallSite)) {
				final Statement allocSite = wrappedAllocSite.stmt();
				if (!wrappedCallSite.getVarName().equals(varName))
					continue;

				InvokeExpr invoker = callSite.getInvokeExpr();
				if (callSite.equals(allocSite)) {
					// TODO no retrieve
					varVal.put(retrieveConstantFromValue(invoker.getArg(wrappedCallSite.getIndex())),
							new CallSiteWithExtractedValue(wrappedCallSite, wrappedAllocSite));
				} else if (allocSite.isAssign()) {
					if (wrappedAllocSite.getValue().isConstant()) {
						String retrieveConstantFromValue = retrieveConstantFromValue(wrappedAllocSite.getValue());
						int pos = -1;

						for (int i = 0; i < invoker.getArgs().size(); i++) {
							Val allocVal = allocSite.getLeftOp();
							Val parameterVal = invoker.getArg(i);

							if (allocVal.equals(parameterVal)) {
								pos = i;
							}
						}

						if (pos > -1 && SootUtils.getParameterType(invoker.getMethod(), pos).isBooleanType()) {
							varVal.put("0".equals(retrieveConstantFromValue) ? "false" : "true", new CallSiteWithExtractedValue(wrappedCallSite, wrappedAllocSite));
						} else {
							varVal.put(retrieveConstantFromValue, new CallSiteWithExtractedValue(wrappedCallSite, wrappedAllocSite));
						}
					} else if (wrappedAllocSite.getValue().isNewExpr()) {
						varVal.putAll(extractSootArray(wrappedCallSite, wrappedAllocSite));
					}
				}
			}
		}
		return varVal;
	}

	/***
	 * Function that finds the values assigned to a soot array.
	 * 
	 * @param callSite   call site at which sootValue is involved
	 * @param allocSite  allocation site at which sootValue is involved
	 * @return extracted array values
	 */
	protected Map<String, CallSiteWithExtractedValue> extractSootArray(CallSiteWithParamIndex callSite,
			ExtractedValue allocSite) {
		Val arrayLocal = allocSite.getValue();
		Method method = allocSite.stmt().getMethod();

		Map<String, CallSiteWithExtractedValue> arrVal = Maps.newHashMap();

		for (Statement statement : method.getStatements()) {
			if (!statement.isAssign()) {
				continue;
			}

			Val leftVal = statement.getLeftOp();
			Val rightVal = statement.getRightOp();

			if (leftVal.equals(arrayLocal) && !rightVal.toString().contains("newarray")) {
				arrVal.put(retrieveConstantFromValue(rightVal), new CallSiteWithExtractedValue(callSite, allocSite));
			}
		}

		/*Body methodBody = allocSite.stmt().getMethod().getActiveBody();

		if (methodBody == null)
			return arrVal;

		Iterator<Unit> unitIterator = methodBody.getUnits().snapshotIterator();
		while (unitIterator.hasNext()) {
			final Unit unit = unitIterator.next();
			if (!(unit instanceof AssignStmt))
				continue;
			AssignStmt uStmt = (AssignStmt) (unit);
			Value leftValue = uStmt.getLeftOp();
			Value rightValue = uStmt.getRightOp();
			if (leftValue.toString().contains(arrayLocal.toString()) && !rightValue.toString().contains("newarray")) {
				arrVal.put(retrieveConstantFromValue(rightValue), new CallSiteWithExtractedValue(callSite, allocSite));
			}
		}*/
		return arrVal;
	}

	private String retrieveConstantFromValue(Val val) {
		if (val.isStringConstant()) {
			return val.getStringValue();
		} else if (val.isIntConstant()) {
			return String.valueOf(val.getIntValue());
		} else if (val.isLongConstant()) {
			return String.valueOf(val.getLongValue()).replaceAll("L", "");
		} else {
			return "";
		}
	}

	protected Map<Integer, Val> extractArray(ExtractedValue extractedValue) {
		Map<Integer, Val> result = new HashMap<>();

		Statement statement = extractedValue.stmt();
		if (!statement.isAssign()) {
			return result;
		}

		Val leftOp = statement.getLeftOp();
		Val rightOp = statement.getRightOp();
		if (!rightOp.isArrayAllocationVal()) {
			return result;
		}

		AllocVal allocVal = new AllocVal(leftOp, statement, rightOp);
		for (Statement successor : statement.getMethod().getControlFlowGraph().getSuccsOf(statement)) {
			ForwardQuery forwardQuery = new ForwardQuery(new ControlFlowGraph.Edge(statement, successor), allocVal);

			Boomerang solver = new Boomerang(context.getObject().getScanner().callGraph(), context.getObject().getScanner().getDataFlowScope());
			ForwardBoomerangResults<?> results = solver.solve(forwardQuery);

			for (Table.Cell<ControlFlowGraph.Edge, Val, ?> entry : results.asStatementValWeightTable().cellSet()) {
				Statement stmt = entry.getRowKey().getStart();
				if (!stmt.isArrayStore()) {
					continue;
				}

				Val arrayBase = stmt.getLeftOp().getArrayBase().getX();
				Integer index = stmt.getLeftOp().getArrayBase().getY();
				if (!arrayBase.equals(allocVal.getDelegate())) {
					continue;
				}

				// TODO
				ControlFlowGraph.Edge edge = new ControlFlowGraph.Edge(stmt.getMethod().getControlFlowGraph().getPredsOf(stmt).stream().findFirst().get(), stmt);
				BackwardQuery backwardQuery = BackwardQuery.make(edge, stmt.getRightOp());

				Boomerang indexSolver = new Boomerang(context.getObject().getScanner().callGraph(), context.getObject().getScanner().getDataFlowScope(), new IntAndStringBoomerangOptions());
				BackwardBoomerangResults<?> indexValue = indexSolver.solve(backwardQuery);

				for (ForwardQuery allocSite : indexValue.getAllocationSites().keySet()) {
					Statement allocStmt = allocSite.cfgEdge().getStart();

					if (!allocStmt.isAssign()) {
						continue;
					}

					result.put(index, allocStmt.getRightOp());
				}
			}
		}

		return result;
	}

	/**
	 * If the {@link crypto.extractparameter.ExtractParameterAnalysis} cannot find the allocation site of a parameter,
	 * it adds the ZERO value to the results to indicate that the value could not be extracted. In such a case, a
	 * {@link ImpreciseValueExtractionError} is reported.
	 *
	 * @param extractedValueMap the map from the {@link #extractValueAsString(String)} method
	 * @param constraint the constraint that cannot be evaluated
	 * @return true if the value could not be extracted and an {@link ImpreciseValueExtractionError} got reported
	 */
	protected boolean couldNotExtractValues(Map<String, CallSiteWithExtractedValue> extractedValueMap, ISLConstraint constraint) {
		if (extractedValueMap.size() != 1) {
			return false;
		}

		for (CallSiteWithExtractedValue callSite : extractedValueMap.values()) {
			Statement statement = callSite.getCallSite().stmt();
			Val extractedVal = callSite.getVal().getValue();

			if (extractedVal.equals(Val.zero())) {
				ImpreciseValueExtractionError extractionError = new ImpreciseValueExtractionError(context.getObject(), statement, context.getSpecification(), constraint);
				errors.add(extractionError);
				return true;
			}
		}
		return false;
	}

}
