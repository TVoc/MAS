/*
 * Copyright (C) 2011-2015 Rinde van Lon, iMinds-DistriNet, KU Leuven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.rinde.rinsim.core.model.road;

import be.kuleuven.cs.mas.vision.Direction;

import com.github.rinde.rinsim.core.TimeLapse;
import com.github.rinde.rinsim.event.Event;
import com.github.rinde.rinsim.event.EventAPI;
import com.github.rinde.rinsim.event.Listener;
import com.github.rinde.rinsim.geom.Connection;
import com.github.rinde.rinsim.geom.ConnectionData;
import com.github.rinde.rinsim.geom.ListenableGraph;
import com.github.rinde.rinsim.geom.ListenableGraph.GraphEvent;
import com.github.rinde.rinsim.geom.Point;
import com.github.rinde.rinsim.util.CategoryMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.primitives.Doubles;

import javax.measure.quantity.Duration;
import javax.measure.quantity.Length;
import javax.measure.quantity.Velocity;
import javax.measure.unit.NonSI;
import javax.measure.unit.SI;
import javax.measure.unit.Unit;

import java.util.Collection;
import java.util.Queue;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Verify.verify;

/**
 * Graph road model that avoids collisions between {@link RoadUser}s. When a
 * dead lock situation arises a {@link DeadlockException} is thrown, note that a
 * grid lock situation (spanning multiple connections) is not detected.
 * Instances can be obtained via a dedicated builder, see
 * {@link #builder(ListenableGraph)}.
 * <p>
 * The graph can be modified at runtime, for information about modifying the
 * graph see {@link DynamicGraphRoadModel}.
 * @author Rinde van Lon
 */
public class CollisionGraphRoadModel extends DynamicGraphRoadModel implements Listener {
	private final double minConnLength;
	private final double vehicleLength;
	private final double minDistance;
	private final SetMultimap<RoadUser, Point> occupiedNodes;

	CollisionGraphRoadModel(Builder builder, double pMinConnLength) {
		super(builder.graph, builder.distanceUnit, builder.speedUnit);
		vehicleLength = unitConversion.toInDist(builder.vehicleLength);
		minDistance = unitConversion.toInDist(builder.minDistance);
		minConnLength = unitConversion.toInDist(pMinConnLength);
		occupiedNodes = Multimaps.synchronizedSetMultimap(CategoryMap
				.<RoadUser, Point> create());
		builder.graph.getEventAPI().addListener(
				new ModificationChecker(minConnLength),
				ListenableGraph.EventTypes.ADD_CONNECTION,
				ListenableGraph.EventTypes.CHANGE_CONNECTION_DATA);
		builder.graph.getEventAPI().addListener(this);
	}

	@Override
	protected MoveProgress doFollowPath(MovingRoadUser object, Queue<Point> path,
			TimeLapse time) {
		if (occupiedNodes.containsKey(object)) {
			occupiedNodes.removeAll(object);
		}
		final MoveProgress mp;
		try {
			mp = super.doFollowPath(object, path, time);
		} catch (final IllegalArgumentException e) {
			throw e;
		} finally {
			// detects if the new location of the object occupies a node
			final Loc loc = objLocs.get(object);
			if (loc.isOnConnection()) {
				if (loc.relativePos < vehicleLength + minDistance) {
					verify(occupiedNodes.put(object, loc.conn.get().from()));
				}
				if (loc.relativePos > loc.connLength - vehicleLength - minDistance) {
					occupiedNodes.put(object, loc.conn.get().to());
				}
			} else {
				occupiedNodes.put(object, loc);
			}
		}
		return mp;
	}

	@Override
	protected double computeTravelableDistance(Loc from, Point to, double speed,
			long timeLeft, Unit<Duration> timeUnit) {
		double closestDist = Double.POSITIVE_INFINITY;
		if (!from.equals(to)) {
			final Connection<?> conn = getConnection(from, to);
			// check if the node is occupied
			if (occupiedNodes.containsValue(conn.to())) {
				closestDist = (from.isOnConnection()
						? from.connLength - from.relativePos
								: conn.getLength())
								- vehicleLength - minDistance;
			}
			// check if there is an obstacle on the connection
			if (connMap.containsKey(conn)) {
				// if yes, how far is it from 'from'
				final Collection<RoadUser> potentialObstacles = connMap.get(conn);
				for (final RoadUser ru : potentialObstacles) {
					final Loc loc = objLocs.get(ru);
					if (loc.isOnConnection() && loc.relativePos > from.relativePos) {
						final double dist = loc.relativePos - from.relativePos
								- vehicleLength - minDistance;
						if (dist < closestDist) {
							closestDist = dist;
						}
					}
				}
			}

		}
		verify(closestDist >= 0d, "", from, to);
		return Math.min(closestDist,
				super.computeTravelableDistance(from, to, speed, timeLeft, timeUnit));
	}

	@Override
	protected void checkMoveValidity(MovingRoadUser user, Loc objLoc, Point nextHop) {
		super.checkMoveValidity(user, objLoc, nextHop);
		// check if there is a vehicle driving in the opposite direction
		if (!objLoc.equals(nextHop)) {
			final Connection<?> conn = getConnection(objLoc, nextHop);
			if (graph.hasConnection(conn.to(), conn.from())
					&& this.isObstructedOn(user, objLoc, conn)) {
				throw new DeadlockException(conn);
			}
//			if (graph.hasConnection(conn.to(), conn.from())
//					&& connMap.containsKey(graph.getConnection(conn.to(), conn.from()))) {
//				throw new DeadlockException(conn);
//			}
		}
	}

	@Override
	public void addObjectAt(RoadUser newObj, Point pos) {
		if (newObj instanceof MovingRoadUser) {
			checkArgument(!occupiedNodes.containsValue(pos),
					"An object can not be added on an already occupied position %s.", pos);
			occupiedNodes.put(newObj, pos);
		}
		super.addObjectAt(newObj, pos);
	}

	@Override
	@Deprecated
	public void addObjectAtSamePosition(RoadUser newObj, RoadUser existingObj) {
		throw new UnsupportedOperationException(
				"Vehicles can not be added at the same position.");
	}

	@Override
	public void removeObject(RoadUser object) {
		checkArgument(objLocs.containsKey(object),
				"RoadUser: %s does not exist.", object);
		occupiedNodes.removeAll(object);
		super.removeObject(object);
	}

	/**
	 * Checks whether the specified node is occupied.
	 * @param node The node to check for occupancy.
	 * @return <code>true</code> if the specified node is occupied,
	 *         <code>false</code> otherwise.
	 */
	public boolean isOccupied(Point node) {
		return occupiedNodes.containsValue(node);
	}
	
	public boolean isOccupied(Point node, RoadUser ignore) {
		for (RoadUser roadUser : occupiedNodes.keySet()) {
			if (roadUser instanceof MovingRoadUser && ! ignore.equals(roadUser)) {
				if (occupiedNodes.get(roadUser).contains(node)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return A read-only <b>indeterministic</b> ordered copy of all currently
	 *         occupied nodes in the graph.
	 */
	public ImmutableSet<Point> getOccupiedNodes() {
		ImmutableSet<Point> set;
		synchronized (occupiedNodes) {
			set = ImmutableSet.copyOf(occupiedNodes.values());
		}
		return set;
	}

	/**
	 * @return The length of all vehicles. The length is expressed in the unit as
	 *         specified by {@link #getDistanceUnit()}.
	 */
	public double getVehicleLength() {
		return vehicleLength;
	}

	/**
	 * @return The minimum distance vehicles need to be apart from each other. The
	 *         length is expressed in the unit as specified by
	 *         {@link #getDistanceUnit()}.
	 */
	public double getMinDistance() {
		return minDistance;
	}

	/**
	 * @return The minimum length all connections need to have in the graph. The
	 *         length is expressed in the unit as specified by
	 *         {@link #getDistanceUnit()}.
	 */
	public double getMinConnLength() {
		return minConnLength;
	}

	/**
	 * Checks whether there is a {@link RoadUser} on the connection between
	 * <code>from</code> and <code>to</code>.
	 * @param from The start point of a connection.
	 * @param to The end point of a connection.
	 * @return <code>true</code> if a {@link RoadUser} occupies either
	 *         <code>from</code>, <code>to</code> or the connection between
	 *         <code>from</code> and <code>to</code>, <code>false</code>
	 *         otherwise.
	 * @throws IllegalArgumentException if no connection exists between
	 *           <code>from</code> and <code>to</code>.
	 */
	public boolean hasRoadUserOn(Point from, Point to, RoadUser ignore) {
		checkArgument(graph.hasConnection(from, to),
				"There is no connection between %s and %s.", from, to);
		return this.connKeyHasMovingRoadUser(graph.getConnection(from, to), ignore)
				|| this.posKeyHasMovingRoadUser(from, ignore) || this.posKeyHasMovingRoadUser(to, ignore)
				|| (graph.hasConnection(to, from) ? this.connKeyHasMovingRoadUser(graph.getConnection(to, from), ignore) : false);
	}

	/**
	 * Checks whether there is a {@link RoadUser} on the connection between
	 * <code>from</code> and <code>to</code>.
	 * @param from The start point of a connection.
	 * @param to The end point of a connection.
	 * @return <code>true</code> if a {@link RoadUser} occupies either
	 *         <code>from</code> or the connection between
	 *         <code>from</code> and <code>to</code>, <code>false</code>
	 *         otherwise.
	 * @throws IllegalArgumentException if no connection exists between
	 *           <code>from</code> and <code>to</code>.
	 */
	public boolean hasRoadUserOnIgnoreTo(Point from, Point to, RoadUser ignore) {
		checkArgument(graph.hasConnection(from, to),
				"There is no connection between %s and %s.", from, to);
		return this.connKeyHasMovingRoadUser(graph.getConnection(from, to), ignore)
				|| this.posKeyHasMovingRoadUser(from, ignore)
				|| (graph.hasConnection(to, from) ? this.connKeyHasMovingRoadUser(graph.getConnection(to, from), ignore) : false);
	}

	/**
	 * Checks whether there is a {@link RoadUser} on the connection between
	 * <code>from</code> and <code>to</code>.
	 * @param from The start point of a connection.
	 * @param to The end point of a connection.
	 * @return <code>true</code> if a {@link RoadUser} occupies either
	 *         <code>to</code> or the connection between
	 *         <code>from</code> and <code>to</code>, <code>false</code>
	 *         otherwise.
	 * @throws IllegalArgumentException if no connection exists between
	 *           <code>from</code> and <code>to</code>.
	 */
	public boolean hasRoadUserOnIgnoreFrom(Point from, Point to, RoadUser ignore) {
		checkArgument(graph.hasConnection(from, to),
				"There is no connection between %s and %s.", from, to);
		return this.connKeyHasMovingRoadUser(graph.getConnection(from, to), ignore)
				|| this.posKeyHasMovingRoadUser(to, ignore)
				|| (graph.hasConnection(to, from) ? this.connKeyHasMovingRoadUser(graph.getConnection(to, from), ignore) : false);
	}

	/**
	 * Checks whether there is a {@link RoadUser} on the connection between
	 * <code>from</code> and <code>to</code>.
	 * @param from The start point of a connection.
	 * @param to The end point of a connection.
	 * @return <code>true</code> if a {@link RoadUser} occupies the connection between
	 *         <code>from</code> and <code>to</code>, <code>false</code>
	 *         otherwise.
	 * @throws IllegalArgumentException if no connection exists between
	 *           <code>from</code> and <code>to</code>.
	 */
	public boolean hasRoadUserOnIgnoreFromAndTo(Point from, Point to, RoadUser ignore) {
		try {
			checkArgument(graph.hasConnection(from, to),
					"There is no connection between %s and %s.", from, to);
			return this.connKeyHasMovingRoadUser(graph.getConnection(from, to), ignore)
					|| (graph.hasConnection(to, from) ? this.connKeyHasMovingRoadUser(graph.getConnection(to, from), ignore) : false);
		} catch(IllegalArgumentException e) {
			graph.addConnection(from, to);
			System.err.println("somehow connection between " + from + " and " + to + " was removed; it was readded");
			return this.connKeyHasMovingRoadUser(graph.getConnection(from, to), ignore)
					|| (graph.hasConnection(to, from) ? this.connKeyHasMovingRoadUser(graph.getConnection(to, from), ignore) : false);
		}
	}

	/**
	 * True if roadUser is between reference and to
	 */
	public boolean occupiesPointWithRespectTo(RoadUser roadUser, Point to, Point reference) {
		if (! connMap.containsValue(roadUser) || ! this.isOnConnection(this.getConnection(roadUser).get(), to)) {
			return false;
		}

		return this.doOccupiesPointWithRespectTo(this.getPosition(roadUser), to, reference);
	}
	
	public boolean occupiesPoint(RoadUser roadUser, Point point) {
		return occupiedNodes.get(roadUser).contains(point);
	}
	
	public boolean occupiesOneOfPoints(RoadUser roadUser, Set<Point> points) {
		for (Point ele : occupiedNodes.get(roadUser)) {
			if (points.contains(ele)) {
				return true;
			}
		}
		
		return false;
	}
	
	public Set<Point> getOccupiedPoints(RoadUser user) {
		return occupiedNodes.get(user);
	}

	/**
	 * to should be the end point of a connection. True if roadUserPosition is between to and reference
	 */
	public boolean occupiesPointWithRespectTo(Point roadUserPosition, Point to, Point reference) {
		boolean foundConnection = false;

		for (Point incoming : this.getGraph().getIncomingConnections(to)) {
			Connection<? extends ConnectionData> toConsider = this.getConnection(incoming, to);
			if (this.isOnConnection(toConsider, roadUserPosition)) {
				foundConnection = true;
				break;
			}
		}

		return foundConnection ? this.doOccupiesPointWithRespectTo(roadUserPosition, to, reference) : false;
	}
	
	public boolean isObstructedOn(RoadUser roadUser, Connection connection) {
		Direction obstructionDirection = Direction.determineDirectionOf(this.getPosition(roadUser), connection.to());
		for (RoadUser user : occupiedNodes.keySet()) {
			if (user instanceof MovingRoadUser && ! user.equals(roadUser)) {
				if (occupiedNodes.get(user).contains(connection.to())) {
					return true;
				}
			}
		}
//		if (posMap.containsKey(connection.to())) {
//			for (RoadUser user : posMap.get(connection.to())) {
//				if (user instanceof MovingRoadUser) {
//					return true;
//				}
//			}
//		}
		for (RoadUser ele : connMap.get(connection)) {
			if (ele.equals(roadUser)) {
				continue;
			}
			if (obstructionDirection.equals(Direction.determineDirectionOf(this.getPosition(roadUser), this.getPosition(ele))))
			{
				return true;
			}
		}
		return false;
	}
	
	private boolean isObstructedOn(RoadUser roadUser, Point roadUserPoint, Connection connection) {
		Direction obstructionDirection = Direction.determineDirectionOf(roadUserPoint, connection.to());
		for (RoadUser user : occupiedNodes.keySet()) {
			if (user instanceof MovingRoadUser && ! user.equals(roadUser)) {
				if (occupiedNodes.get(user).contains(connection.to())) {
					return true;
				}
			}
		}
//		if (posMap.containsKey(connection.to())) {
//			for (RoadUser user : posMap.get(connection.to())) {
//				if (user instanceof MovingRoadUser) {
//					return true;
//				}
//			}
//		}
		for (RoadUser ele : connMap.get(connection)) {
			if (ele.equals(roadUser)) {
				continue;
			}
			if (obstructionDirection.equals(Direction.determineDirectionOf(this.getPosition(roadUser), this.getPosition(ele))))
			{
				return true;
			}
		}
		return false;
	}

	protected boolean doOccupiesPointWithRespectTo(Point roadUserPosition, Point to, Point reference) {
		if (roadUserPosition.equals(to) || roadUserPosition.equals(reference)) {
			return false;
		}

		Direction posTo = Direction.determineDirectionOf(roadUserPosition, to);
		Direction posRef = Direction.determineDirectionOf(roadUserPosition, reference);

		return ! posTo.equals(posRef);
	}

	private boolean connKeyHasMovingRoadUser(Connection<?> key, RoadUser ignore) {
		for (RoadUser user : connMap.get(key)) {
			if (user instanceof MovingRoadUser && ! user.equals(ignore)) {
				return true;
			}
		}
		return false;
	}

	private boolean posKeyHasMovingRoadUser(Point key, RoadUser ignore) {
		for (RoadUser user : occupiedNodes.keySet()) {
			if (user instanceof MovingRoadUser && ! user.equals(ignore)) {
				if (occupiedNodes.get(user).contains(key)) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public void handleEvent(Event e) {
		System.err.println("graph changed!");
	}

	/**
	 * Create a {@link Builder} for constructing {@link CollisionGraphRoadModel}
	 * instances. Note that all connections in the specified graph must have
	 * length <code>2 * vehicleLength</code>, where vehicle length can be
	 * specified in {@link Builder#setVehicleLength(double)}.
	 * @param graph A {@link ListenableGraph}.
	 * @return A new {@link Builder} instance.
	 */
	public static Builder builder(ListenableGraph<?> graph) {
		return new Builder(graph);
	}

	static void checkConnectionLength(double minConnLength, Connection<?> conn) {
		checkArgument(
				Point.distance(conn.from(), conn.to()) >= minConnLength,
				"Invalid graph: the minimum connection length is %s, connection %s->%s is too short.",
				minConnLength, conn.from(), conn.to());
		checkArgument(
				conn.getLength() >= minConnLength,
				"Invalid graph: the minimum connection length is %s, connection %s->%s defines length data that is too short: %s.",
				minConnLength, conn.from(), conn.to(), conn.getLength());
	}

	/**
	 * A builder for constructing {@link CollisionGraphRoadModel} instances. Use
	 * {@link CollisionGraphRoadModel#builder(ListenableGraph)} for obtaining
	 * builder instances.
	 * @author Rinde van Lon
	 */
	public static final class Builder {
		/**
		 * The default distance unit: {@link SI#METER}.
		 */
		public static final Unit<Length> DEFAULT_DISTANCE_UNIT = SI.METER;

		/**
		 * The default speed unit: {@link NonSI#KILOMETERS_PER_HOUR}.
		 */
		public static final Unit<Velocity> DEFAULT_SPEED_UNIT = NonSI.KILOMETERS_PER_HOUR;

		/**
		 * The default vehicle length: <code>2</code>.
		 */
		public static final double DEFAULT_VEHICLE_LENGTH = 2;

		/**
		 * The default minimum distance: <code>.25</code>.
		 */
		public static final double DEFAULT_MIN_DISTANCE = .25;

		final ListenableGraph<?> graph;
		Unit<Length> distanceUnit;
		Unit<Velocity> speedUnit;
		double vehicleLength;
		double minDistance;

		Builder(ListenableGraph<?> g) {
			graph = g;
			distanceUnit = DEFAULT_DISTANCE_UNIT;
			speedUnit = DEFAULT_SPEED_UNIT;
			vehicleLength = DEFAULT_VEHICLE_LENGTH;
			minDistance = DEFAULT_MIN_DISTANCE;
		}

		/**
		 * Sets the distance unit used to interpret all coordinates and distances,
		 * including those of the supplied {@link ListenableGraph}. The default
		 * value is {@link #DEFAULT_DISTANCE_UNIT}.
		 * @param unit The unit to set.
		 * @return This, as per the builder pattern.
		 */
		public Builder setDistanceUnit(Unit<Length> unit) {
			distanceUnit = unit;
			return this;
		}

		/**
		 * Sets the speed unit used to interpret the speeds of all vehicles. The
		 * default value is {@link #DEFAULT_SPEED_UNIT}.
		 * @param unit The unit to set.
		 * @return This, as per the builder pattern.
		 */
		public Builder setSpeedUnit(Unit<Velocity> unit) {
			speedUnit = unit;
			return this;
		}

		/**
		 * Sets the length of each vehicle added to the
		 * {@link CollisionGraphRoadModel} that will be constructed by this builder.
		 * The vehicle length must be a strictly positive number. The default value
		 * is {@link #DEFAULT_VEHICLE_LENGTH}.
		 * @param length A length expressed in the unit set by
		 *          {@link #setDistanceUnit(Unit)}.
		 * @return This, as per the builder pattern.
		 */
		public Builder setVehicleLength(double length) {
			checkArgument(length > 0d,
					"Only positive vehicle lengths are allowed, found %s.", length);
			checkArgument(Doubles.isFinite(length),
					"%s is not a valid vehicle length.", length);
			vehicleLength = length;
			return this;
		}

		/**
		 * Sets the minimum required distance between two vehicles. The minimum
		 * distance must be a positive number &le; to 2 * vehicle length. The
		 * default value is {@link #DEFAULT_MIN_DISTANCE}.
		 * @param dist A distance expressed in the unit set by
		 *          {@link #setDistanceUnit(Unit)}.
		 * @return This, as per the builder pattern.
		 */
		public Builder setMinDistance(double dist) {
			checkArgument(dist >= 0d);
			minDistance = dist;
			return this;
		}

		/**
		 * @return A new {@link CollisionGraphRoadModel} instance.
		 */
		public CollisionGraphRoadModel build() {
			final double minConnectionLength = vehicleLength;
			checkArgument(
					minDistance <= minConnectionLength,
					"Min distance must be smaller than 2 * vehicle length (%s), but is %s.",
					vehicleLength, minDistance);
			for (final Connection<?> conn : graph.getConnections()) {
				checkConnectionLength(minConnectionLength, conn);
			}
			return new CollisionGraphRoadModel(this, minConnectionLength);
		}
	}

	static class ModificationChecker implements Listener {
		private final double minConnLength;

		ModificationChecker(double minLength) {
			minConnLength = minLength;
		}

		@Override
		public void handleEvent(Event e) {
			verify(e instanceof GraphEvent);
			final GraphEvent event = (GraphEvent) e;
			checkConnectionLength(minConnLength, event.getConnection());
		}
	}
}
