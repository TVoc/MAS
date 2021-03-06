package be.kuleuven.cs.mas.agent;

import be.kuleuven.cs.mas.architecture.AgentState;
import be.kuleuven.cs.mas.architecture.FollowGradientFieldState;
import be.kuleuven.cs.mas.gradientfield.FieldEmitter;
import be.kuleuven.cs.mas.gradientfield.GradientModel;
import be.kuleuven.cs.mas.message.AgentMessage;
import be.kuleuven.cs.mas.message.AgentMessageBuilder;
import be.kuleuven.cs.mas.parcel.TimeAwareParcel;
import be.kuleuven.cs.mas.strategy.FieldStrategy;
import be.kuleuven.cs.mas.vision.VisualSensor;
import be.kuleuven.cs.mas.vision.VisualSensorOwner;

import com.github.rinde.rinsim.core.TimeLapse;
import com.github.rinde.rinsim.core.model.comm.CommDevice;
import com.github.rinde.rinsim.core.model.comm.CommDeviceBuilder;
import com.github.rinde.rinsim.core.model.comm.CommUser;
import com.github.rinde.rinsim.core.model.comm.Message;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.Vehicle;
import com.github.rinde.rinsim.core.model.road.CollisionGraphRoadModel;
import com.github.rinde.rinsim.core.model.road.MovingRoadUser;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.geom.Connection;
import com.github.rinde.rinsim.geom.ConnectionData;
import com.github.rinde.rinsim.geom.Point;
import com.google.common.base.Optional;

import org.apache.commons.math3.random.RandomGenerator;

import javax.annotation.Nullable;

import java.util.*;

public class AGVAgent extends Vehicle implements MovingRoadUser, FieldEmitter, CommUser, VisualSensorOwner {

	@Nullable
	private GradientModel gradientModel;

	private final RandomGenerator rng;
	private final FieldStrategy fieldStrategy;
	private Optional<CollisionGraphRoadModel> roadModel;
	private Optional<PDPModel> pdpModel;
	private Optional<Point> nextPointDestination;
	private Queue<Point> path;
	private Optional<CommDevice> commDevice;
	private Optional<TimeAwareParcel> parcel;
	private AgentState state;
	private VisualSensor sensor;
	private AgentMessageBuilder msgBuilder;
	private String name;
	private Point mostRecentPosition;

	public AGVAgent(RandomGenerator r, FieldStrategy fieldStrategy, int visualRange, Point startPosition, String name,
			double capacity) {
		rng = r;
		this.fieldStrategy = fieldStrategy;
		roadModel = Optional.absent();
		pdpModel = Optional.absent();
		nextPointDestination = Optional.absent();
		path = new LinkedList<>();
		commDevice = Optional.absent();
		parcel = Optional.absent();
		state = new FollowGradientFieldState(this);
		sensor = new VisualSensor(this, visualRange);
		this.msgBuilder = new AgentMessageBuilder();
		this.setStartPosition(startPosition);
		this.mostRecentPosition = startPosition;
		this.name = name;
		this.setCapacity(capacity);
	}

	public void initRoadPDP(RoadModel roadModel, PDPModel pdpModel) {
		this.roadModel = Optional.of((CollisionGraphRoadModel) roadModel);
		this.pdpModel = Optional.of(pdpModel);
	}

	@Override
	public double getSpeed() {
		return 1;
	}

	@Override
	public void tickImpl(TimeLapse timeLapse) {
		// TODO move all agent behaviour to states
		if (this.getRoadModel().getGraph().containsNode(this.getPosition().get())) {
			this.setMostRecentPosition(this.getPosition().get());
		}

		for (Message msg : this.getCommDevice().getUnreadMessages()) {
			this.getAgentState().processMessage((AgentMessage) msg.getContents());
		}

		this.getAgentState().act(timeLapse);
	}

	public void followPath(TimeLapse timeLapse) {
		this.getRoadModel().followPath(this, this.getPath(), timeLapse);
	}

	public void followPath(Point point, TimeLapse timeLapse) {
		this.getRoadModel().followPath(this, new LinkedList<>(Arrays.asList(point)), timeLapse);
	}

	@Override
	public void afterTick(TimeLapse timeLapse) {}

	public GradientModel getGradientModel() {
		return gradientModel;
	}

	@Override
	public void setGradientModel(GradientModel gradientModel) {
		this.gradientModel = gradientModel;
	}

	@Override
	public double getStrength() {
		return state.getFieldStrength();
	}

	@Override
	public Optional<Point> getPosition() {
		if (! this.roadModel.isPresent()) {
			return Optional.absent();
		}
		return Optional.of(this.roadModel.get().getPosition(this));
	}

	@Override
	public Optional<Point> getLastPosition() {
		if (getMostRecentPosition() == null) {
			return Optional.absent();
		} else {
			return Optional.of(getMostRecentPosition());
		}
	}

	public Point getMostRecentPosition() {
		return this.mostRecentPosition;
	}

	private void setMostRecentPosition(Point point) {
		this.mostRecentPosition = point;
	}

	public void sendMessage(AgentMessage msg) throws IllegalStateException {
		if (! this.commDevice.isPresent()) {
			throw new IllegalStateException("comm device not set");
		}
		this.commDevice.get().broadcast(msg);
	}

	public CommDevice getCommDevice() {
		return this.commDevice.get();
	}

	@Override
	public void setCommDevice(CommDeviceBuilder builder) {
		this.commDevice = Optional.of(builder.build());
	}

	public Optional<TimeAwareParcel> getParcel() {
		return this.parcel;
	}

	public void setParcel(TimeAwareParcel parcel) {
		assert(! this.getParcel().isPresent());
		this.parcel = Optional.of(parcel);
		this.replanRoute();
	}

	public void unsetParcel() {
		assert(this.getParcel().isPresent());
		this.parcel = Optional.absent();
	}

	public AgentState getAgentState() {
		return this.state;
	}

	public void setAgentState(AgentState state) {
		this.state = state;
		state.uponSet();
	}

	@Override
	public CollisionGraphRoadModel getRoadModel() {
		return this.roadModel.get();
	}

	public PDPModel getPDPModel() {
		return this.pdpModel.get();
	}

	private VisualSensor getVisualSensor() {
		return this.sensor;
	}

	public boolean occupiedPointsOnPathWithinRange() {
		Set<Point> occupiedPoints = this.getOccupiedPointsInVisualRange();

		// the following only works under the assumption that a queue's iterator returns its elements in the logical order,
		// which seems to be satisfied if the queue is a linked list
		Iterator<Point> pointIt = this.getPath().iterator();
		Point pathPoint;
		int i = 0;
		while (pointIt.hasNext() && i < this.getVisualSensor().getVisualRange()) {
			pathPoint = pointIt.next();
			i++;
			if (occupiedPoints.contains(pathPoint)) {
				return true;
			}
			if (this.getParcel().get().getDestination().equals(pathPoint)) {
				// the path to this agent's destination is currently clear of obstacles, so return false
				return false;
			}
		}
		return false;
	}

	public AgentMessageBuilder getMessageBuilder() {
		return this.msgBuilder;
	}

	public Set<Point> getOccupiedPointsInVisualRange() {
		return this.getVisualSensor().getOccupiedPointsWithinVisualRange();
	}

	public Optional<Point> getNextPointOnPath() {
		Point toReturn = this.path.peek();
		if (toReturn == null) {
			return Optional.absent();
		}
		return Optional.of(toReturn);
	}

	private Queue<Point> getPath() {
		return this.path;
	}

	public String getName() {
		return this.name;
	}

	public void replanRoute() {
		if (! this.getParcel().isPresent()) {
			return;
		}
		this.path = new LinkedList<>(this.getRoadModel().getShortestPathTo(this, this.getParcel().get().getDestination()));
	}

	public Optional<Point> getRandomReachablePoint(Set<Point> excludeSet, Set<Point> tryAgain) {
		Set<Point> occupiedPoints = this.getVisualSensor().getOccupiedPointsWithinVisualRange();
		Collection<Point> neighbours = this.getRoadModel().getOutgoingConnections(this.getPosition().get());
		neighbours.removeAll(occupiedPoints);
		neighbours.removeAll(excludeSet);
		neighbours.removeAll(tryAgain);

		if (neighbours.isEmpty()) {
			neighbours = this.getRoadModel().getOutgoingConnections(this.getPosition().get());
			neighbours.removeAll(occupiedPoints);
			neighbours.removeAll(excludeSet);
			if (neighbours.isEmpty()) {
				return Optional.absent();
			}
		}
		ArrayList<Point> asList = new ArrayList<>(neighbours);
		return Optional.of(asList.get(this.rng.nextInt(asList.size())));
	}

	public Optional<Point> getRandomNeighbourPoint(Collection<Point> mustExclude, Collection<Point> tryAgain) {
		if (this.getRoadModel().getGraph().containsNode(this.getPosition().get())) {
			return this.getRandomNeighbourPointIsOnNode(mustExclude, tryAgain, this.getPosition().get());
		} else {
			return this.getRandomNeighbourPointNotOnNode(mustExclude, tryAgain, this.getPosition().get());
		}
	}

	private Optional<Point> getRandomNeighbourPointIsOnNode(Collection<Point> mustExclude, Collection<Point> tryAgain, Point closestPoint) {
		Collection<Point> neighbours = this.getRoadModel().getOutgoingConnections(closestPoint);
		neighbours.removeAll(mustExclude);
		neighbours.removeAll(tryAgain);

		if (neighbours.isEmpty()) {
			neighbours = this.getRoadModel().getOutgoingConnections(closestPoint);
			neighbours.removeAll(mustExclude);
			if (neighbours.isEmpty()) {
				return Optional.absent();
			}
		}
		ArrayList<Point> asList = new ArrayList<>(neighbours);
		Point toReturn = asList.get(this.rng.nextInt(asList.size()));
		if (! this.getRoadModel().getGraph().hasConnection(closestPoint, toReturn)) {
			System.err.println("No connection between " + closestPoint + " and " + toReturn);
		}
		return Optional.of(toReturn);
	}

	private Optional<Point> getRandomNeighbourPointNotOnNode(Collection<Point> mustExclude, Collection<Point> tryAgain, Point closestPoint) {
		Connection<? extends ConnectionData> conn = this.getRoadModel().getConnection(this).get();
		List<Point> chooseBetween = new ArrayList<>(Arrays.asList(conn.from(), conn.to()));
		chooseBetween.removeAll(mustExclude);
		chooseBetween.removeAll(tryAgain);
		if (chooseBetween.isEmpty()) {
			chooseBetween = new ArrayList<>(Arrays.asList(conn.from(), conn.to()));
			chooseBetween.removeAll(mustExclude);
			if (chooseBetween.isEmpty()) {
				return Optional.absent();
			}
		}
		return Optional.of(chooseBetween.get(this.rng.nextInt(chooseBetween.size())));
	}

	public FieldStrategy getFieldStrategy() {
		return fieldStrategy;
	}

	public Collection<Point> getNeighbouringPoints() {
		return this.getRoadModel().getOutgoingConnections(this.getPosition().get());
	}

	public String toString() {
		if (this.getPosition().isPresent()) {
			return this.getName() + ": " + this.getPosition().get();
		}
		return this.getName();
	}
}
