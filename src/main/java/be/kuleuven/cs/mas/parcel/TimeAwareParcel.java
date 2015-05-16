package be.kuleuven.cs.mas.parcel;

import be.kuleuven.cs.mas.gradientfield.FieldEmitter;
import be.kuleuven.cs.mas.gradientfield.GradientModel;
import com.github.rinde.rinsim.core.TimeLapse;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.pdp.Parcel;
import com.github.rinde.rinsim.core.model.road.RoadModel;
import com.github.rinde.rinsim.geom.Point;
import com.github.rinde.rinsim.util.TimeWindow;
import com.google.common.base.Optional;

public class TimeAwareParcel extends Parcel implements FieldEmitter {

	// TODO: Use treshold or make field strength a function of elapsed time?
	public static final long TIME_TRESHOLD = 5000;
	public static final double FIELD_BASIC = -1D;
	public static final double FIELD_PRIORITY = -5D;

	private long waitingSince;
	private Optional<RoadModel> roadModel;
	private Optional<PDPModel> pdpModel;
	private boolean delivered = false;
	private Optional<Point> position;
	private GradientModel gradientModel;
	
	TimeAwareParcel(Point startPosition, Point pDestination,
			double pMagnitude, long currentTime) {
		super(pDestination, 0, TimeWindow.ALWAYS, 0, TimeWindow.ALWAYS,
				pMagnitude);
		setPosition(startPosition);
		this.waitingSince = currentTime;
	}

	@Override
	public void initRoadPDP(RoadModel pRoadModel, PDPModel pPdpModel) {
		this.roadModel = Optional.of(pRoadModel);
		this.pdpModel = Optional.of(pPdpModel);
	}
	
	private Optional<RoadModel> getOwnRoadModel() {
		return this.roadModel;
	}
	
	private Optional<PDPModel> getOwnPDPModel() {
		return this.pdpModel;
	}
	
	private boolean isDelivered() {
		return this.delivered;
	}
	
	private void setDelivered(boolean delivered) {
		this.delivered = delivered;
	}
	
	public void notifyDelivered(TimeLapse time) throws IllegalStateException {
		if (this.isDelivered()) {
			throw new IllegalStateException("parcel was already delivered");
		}
		
		this.getOwnPDPModel().get().unregister(this);
		this.getOwnRoadModel().get().removeObject(this);
		this.setDelivered(true);
		// TODO write relevant variables to experiment result 
	}
	
	public long getWaitingSince() {
		return this.waitingSince;
	}

	/**
	 * Calculates and returns the time elapsed since the parcel was created.
	 *
	 * @return The elapsed time in milliseconds.
	 */
	public long getElapsedTime() {
		return System.currentTimeMillis() - getWaitingSince();
	}

	protected void setPosition(Point position) {
		super.setStartPosition(position);
		this.position = Optional.of(position);
	}

	@Override
	public void setModel(GradientModel model) {
		this.gradientModel = model;
	}

	@Override
	public double getStrength() {
		// TODO: Regulate field strength (as function of <code>waitingSince</code>?)
		return getElapsedTime() >= TIME_TRESHOLD ? FIELD_PRIORITY : FIELD_BASIC;
	}

	@Override
	public Optional<Point> getPosition() {
		return position;
	}

	/**
	 * To be called when the parcel is picked up by an {@link be.kuleuven.cs.mas.AGVAgent}.
	 */
	public void notifyPickup() {
		position = Optional.absent();
	}

}
