package be.kuleuven.cs.mas.architecture;

import be.kuleuven.cs.mas.agent.AGVAgent;
import be.kuleuven.cs.mas.parcel.TimeAwareParcel;

import com.github.rinde.rinsim.core.TimeLapse;
import com.github.rinde.rinsim.geom.Point;
import com.google.common.base.Optional;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class CarryingParcelNoJamState extends CarryingParcelState {

	public CarryingParcelNoJamState(AGVAgent agent, TimeAwareParcel parcel) {
		super(agent);
		agent.setParcel(parcel);
	}
	
	public CarryingParcelNoJamState(AGVAgent agent, List<ReleaseBacklog> backLogs, TimeAwareParcel parcel) {
		super(agent, backLogs);
		agent.setParcel(parcel);
	}
	
	public CarryingParcelNoJamState(AGVAgent agent, boolean replanRoute) {
		super(agent);
		assert(agent.getParcel().isPresent());
		if (replanRoute) {
			agent.replanRoute();
		}
	}
	
	public CarryingParcelNoJamState(AGVAgent agent, List<ReleaseBacklog> backLogs, boolean replanRoute) {
		super(agent, backLogs);
		assert(agent.getParcel().isPresent());
		if (replanRoute) {
			agent.replanRoute();
		}
	}

	@Override
	public void act(TimeLapse timeLapse) {

		Set<Point> occupied = this.getAgent().getOccupiedPointsInVisualRange();
		Point toCheck = this.getAgent().getNextPointOnPath().get();
		if (occupied.contains(toCheck)) {
			System.out.println("Protocol started by " + this.getAgent().getName());
			// we have a traffic jam, so begin protocol
			this.sendMoveAside(this.getAgent().getName(), Arrays.asList(this.getAgent().getName()),
					timeLapse.getTime(), this.getAgent().getParcel().get().getScheduleTime(),
					this.getAgent().getNextPointOnPath().get(), 0);
			this.doStateTransition(Optional.of(new CarryingParcelControllingJamState(this.getAgent(), this.getBackLogs(), timeLapse.getTime())));
		} else {
			// otherwise, move forward
			try {
				// possibly fixes bug due to home free messages that go missing
				this.sendHomeFree();
				this.doMoveForward(this.getAgent().getPosition().get(), timeLapse);
			} catch (IllegalArgumentException e) {
				System.err.println("Point to check was: " + toCheck);
				System.err.println("well, shit");
			}
			
		}
		
		// first of all, check if the parcel can be delivered
		if (this.getAgent().getPosition().get().equals(this.getAgent().getParcel().get().getDestination())) {
			this.getAgent().getPDPModel().deliver(this.getAgent(), this.getAgent().getParcel().get(), timeLapse);
			// if parcel is not in cargo anymore, delivery was successful
			if (! this.getAgent().getPDPModel().containerContains(this.getAgent(), this.getAgent().getParcel().get())) {
				// parcel has been delivered, so follow gradient field now
				this.getAgent().getParcel().get().notifyDelivered(timeLapse);
				this.doStateTransition(Optional.of(new FollowGradientFieldState(this.getAgent(), this.getBackLogs())));
			}
		}
	}
	
	@Override
	protected void handleExceptionDuringMove(TimeLapse timeLapse) {
		// we have a traffic jam, so begin protocol
		this.sendMoveAside(this.getAgent().getName(), Arrays.asList(this.getAgent().getName()),
				timeLapse.getTime(), this.getAgent().getParcel().get().getScheduleTime(),
				this.getAgent().getNextPointOnPath().get(), 0);
		this.doStateTransition(Optional.of(new CarryingParcelControllingJamState(this.getAgent(), this.getBackLogs(), timeLapse.getTime())));
	}
	
	protected void processMoveAsideMessage(MoveAsideMessage msg) {
		if (msg.getRequester().equals(this.getAgent().getName())) {
			// somehow received own move-aside request, so ignore
			return;
		}
		if (msg.getPropagator().equals(this.getAgent().getName())) {
			// somehow received own propagated move-aside request, so ignore
			return;
		}
		if (! (this.getAgent().getPosition().get().equals(msg.getWantPos())
				|| this.getAgent().getNextPointOnPath().get().equals(msg.getWantPos())
				|| this.getAgent().getRoadModel().occupiesPoint(this.getAgent(), msg.getWantPos())
				|| this.getAgent().getRoadModel().occupiesPointWithRespectTo(this.getAgent(), msg.getWantPos(), msg.getAtPos()))) {
			// requester does not want this agent's position, so ignore
			return;
		}

		if (this.trafficPriorityFunction(msg.getRequester(), msg.getParcelWaitingSince())) {
			this.getAgent().getMessageBuilder().addField("reject")
			.addField("requester", msg.getRequester())
			.addField("propagator", msg.getPropagator())
			.addField("timestamp", Long.toString(msg.getTimeStamp()));
			this.getAgent().sendMessage(this.getAgent().getMessageBuilder().build());
		} else {
			this.getAgent().getMessageBuilder().addField("ack")
			.addField("requester", msg.getRequester())
			.addField("propagator", msg.getPropagator())
			.addField("timestamp", Long.toString(msg.getTimeStamp()));
			this.getAgent().sendMessage(this.getAgent().getMessageBuilder().build());
			this.doStateTransition(Optional.of(new CarryingParcelGetOutOfTheWayState(this.getAgent(), this.getBackLogs(), msg.getRequester(), msg.getPropagator(), msg.getWaitForList(), msg.getTimeStamp(), msg.getParcelWaitingSince(), msg.getStep(), msg.getWantPos(), msg.getAtPos())));
		}
	}

	@Override
	protected void doStateTransition(Optional<AgentState> nextState) {
		this.getAgent().setAgentState(nextState.get());
	}

	public void uponSet() {

	}

	@Override
	protected void processReleaseMessage(ReleaseMessage msg) {
		super.processReleaseMessage(msg);
	}

	@Override
	protected void processHomeFreeMessage(HomeFreeMessage msg) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void processRejectMessage(RejectMessage msg) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void processAckMessage(AckMessage msg) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void processPleaseConfirmMessage(PleaseConfirmMessage msg) {
		// TODO Auto-generated method stub
		if (msg.getRequester().equals(this.getAgent().getName()) ||
				msg.getPropagator().equals(this.getAgent().getName())) {
			this.sendNotConfirm(msg.getRequester(), msg.getPropagator(), msg.getTimeStamp(), msg.getConfirmPositions());
		}
	}

	@Override
	protected void processDoConfirmMessage(DoConfirmMessage msg) {
		// TODO Auto-generated method stub
		
	}

	@Override
	protected void processNotConfirmMessage(NotConfirmMessage msg) {
		// TODO Auto-generated method stub
		
	}

}
