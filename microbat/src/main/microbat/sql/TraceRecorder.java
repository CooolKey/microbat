package microbat.sql;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import microbat.handler.xml.VarValueXmlWriter;
import microbat.model.BreakPoint;
import microbat.model.trace.StepVariableRelationEntry;
import microbat.model.trace.Trace;
import microbat.model.trace.TraceNode;
import microbat.model.value.VarValue;
import sav.common.core.utils.CollectionUtils;

public class TraceRecorder extends DbService {
	private static final int READ = 1;
	private static final int WRITE = 2;
	
	public void storeTrace(Trace trace) throws SQLException {
		Connection conn = null;
		List<Statement> stmts = new ArrayList<Statement>();
		try {
			conn = getConnection();
			conn.setAutoCommit(false);
			insertTrace(trace, conn, stmts);
			conn.commit();
		} catch (SQLException e) {
			e.printStackTrace();
			rollback(conn);;
			throw e;
		} finally {
			closeDb(conn, stmts, null);
		}
	}
	
	public int insertTrace(Trace trace, Connection conn, List<Statement> stmts)
			throws SQLException {
		return insertTrace(trace, null, null, null, null, null, conn, stmts);
	}
	
	public int insertTrace(Trace trace, String projectName, String projectVersion, String bugId, String launchClass,
			String launchMethod, Connection conn, List<Statement> stmts) throws SQLException {
		PreparedStatement ps;
		String sql = "INSERT INTO trace (project_name, project_version, bug_id, launch_class, launch_method, generated_time) "
				+ "VALUES (?, ?, ?, ?, ?, ?)";
		ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
		int idx = 1;
		ps.setString(idx++, projectName);
		ps.setString(idx++, projectVersion);
		ps.setString(idx++, bugId);
		ps.setString(idx++, launchClass);
		ps.setString(idx++, launchMethod);
		ps.setTimestamp(idx++, new Timestamp(System.currentTimeMillis()));
		stmts.add(ps);
		ps.execute();
		int traceId = getFirstGeneratedIntCol(ps);
		insertSteps(traceId, trace.getExecutionList(), conn, stmts);
		insertStepVariableRelation(trace, traceId, conn, stmts);
		return traceId;
	}
	
	private void insertSteps(int traceId, List<TraceNode> exectionList, Connection conn,
			List<Statement> stmts) throws SQLException {
		String sql = "INSERT INTO step (trace_id, step_order, control_dominator, step_in, step_over, invocation_parent, loop_parent,"
				+ "location_id, read_vars, written_vars) VALUES (?,?,?,?,?,?,?,?,?,?)";
		PreparedStatement ps = conn.prepareStatement(sql);
		Set<BreakPoint> locations = new HashSet<>();
		for (int i = 0; i < exectionList.size(); i++) {
			TraceNode node = exectionList.get(i);
			int idx = 1;
			ps.setInt(idx++, traceId);
			ps.setInt(idx++, node.getOrder());
			setNodeOrder(ps, idx++, node.getControlDominator());
			setNodeOrder(ps, idx++, node.getStepInNext());
			setNodeOrder(ps, idx++, node.getStepOverNext());
			setNodeOrder(ps, idx++, node.getInvocationParent());
			setNodeOrder(ps, idx++, node.getLoopParent());
			ps.setInt(idx++, node.getBreakPoint().getLineNumber());
			ps.setString(idx++, generateXmlContent(node.getReadVariables()));
			ps.setString(idx++, generateXmlContent(node.getWrittenVariables()));
			ps.addBatch();
			locations.add(node.getBreakPoint());
		}
		ps.executeBatch();
		stmts.add(ps);
		insertLocation(traceId, locations, conn, stmts);
	}

	protected String generateXmlContent(List<VarValue> varValues) {
		if (CollectionUtils.isEmpty(varValues)) {
			return null;
		}
		return VarValueXmlWriter.generateXmlContent(varValues);
	}
	
	private void insertStepVariableRelation(Trace trace, int traceId, Connection conn, List<Statement> stmts)
			throws SQLException {
		String sql = "INSERT INTO stepVariableRelation (var_id, trace_id, step_order, rw) VALUES (?, ?, ?, ?)";
		PreparedStatement ps = conn.prepareStatement(sql);
		
		for (StepVariableRelationEntry entry : trace.getStepVariableTable().values()) {
			for (TraceNode node : entry.getProducers()) {
				int idx = 1;
				ps.setString(idx++, entry.getVarID());
				ps.setInt(idx++, traceId);
				ps.setInt(idx++, node.getOrder());
				ps.setInt(idx++, WRITE);
				ps.addBatch();
			}
			for (TraceNode node : entry.getConsumers()) {
				int idx = 1;
				ps.setString(idx++, entry.getVarID());
				ps.setInt(idx++, traceId);
				ps.setInt(idx++, node.getOrder());
				ps.setInt(idx++, READ);
				ps.addBatch();
			}
		}
		ps.executeBatch();
		stmts.add(ps);
	}

	private void setNodeOrder(PreparedStatement ps, int idx, TraceNode node) throws SQLException {
		if (node != null) {
			ps.setInt(idx, node.getOrder());
		} else {
			ps.setNull(idx, java.sql.Types.INTEGER);
		}
	}
	
	private void insertLocation(int traceId, Set<BreakPoint> locations, Connection conn, List<Statement> stmts)
			throws SQLException {
		String sql = "INSERT INTO location (trace_id, location_id, class_name, line_number, is_conditional, is_return) "
				+ "VALUES (?, ?, ?, ?, ?, ?)";
		PreparedStatement ps = conn.prepareStatement(sql);
		for (BreakPoint location : locations) {
			int idx = 1;
			ps.setInt(idx++, traceId);
			ps.setString(idx++, location.getId());
			ps.setString(idx++, location.getDeclaringCompilationUnitName());
			ps.setInt(idx++, location.getLineNumber());
			ps.setBoolean(idx++, location.isConditional());
			ps.setBoolean(idx++, location.isReturnStatement());
			ps.addBatch();
		}
		ps.executeBatch();
		stmts.add(ps);
	}
	
}