package be.kuleuven.cs.mas.gradientfield;

import be.kuleuven.cs.mas.gradientfield.crawler.DistanceMap;
import be.kuleuven.cs.mas.gradientfield.crawler.GraphCrawler;
import com.github.rinde.rinsim.core.model.AbstractModel;
import com.github.rinde.rinsim.core.model.ModelProvider;
import com.github.rinde.rinsim.core.model.ModelReceiver;
import com.github.rinde.rinsim.core.model.pdp.PDPModel;
import com.github.rinde.rinsim.core.model.road.CollisionGraphRoadModel;
import com.github.rinde.rinsim.geom.ConnectionData;
import com.github.rinde.rinsim.geom.Graph;
import com.github.rinde.rinsim.geom.Point;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GradientModel extends AbstractModel<FieldEmitter> implements ModelReceiver {

    private Graph<? extends ConnectionData> graph;
    private GraphCrawler graphCrawler;

    private Set<FieldEmitter> emitters = new HashSet<>();
    private Map<Point, DistanceMap> distanceMaps = new HashMap<>();

    @Nullable
    private PDPModel pdpModel;

    public DistanceMap getDistanceMap(Point point) {
        if (distanceMaps.containsKey(point)) {
            return distanceMaps.get(point);
        }

        DistanceMap result = graphCrawler.crawl(point);
        distanceMaps.put(point, result);
        return result;
    }

    public double getGradient(Point point, Set<FieldEmitter> excludedEmitters) {
        double influence = 0D;
        for (FieldEmitter emitter : emitters) {
            if (excludedEmitters.contains(emitter)) {
                continue;
            }

            DistanceMap distanceMap = getDistanceMap(emitter.getPosition());
            double emitterInfluence = distanceMap.getMaxDistance() - distanceMap.getDistance(point);
            if (emitterInfluence < 0D) {
                emitterInfluence = 0D;
            }
            influence += emitterInfluence * emitter.getStrength();
        }
        return influence;
    }

    public double getGradient(Point point) {
        return getGradient(point, new HashSet<>());
    }

    @Override
    public boolean register(FieldEmitter element) {
        emitters.add(element);
        return true;
    }

    @Override
    public boolean unregister(FieldEmitter element) {
        emitters.remove(element);
        return true;
    }

    @Override
    public void registerModelProvider(ModelProvider mp) {
        pdpModel = mp.tryGetModel(PDPModel.class);
        graph = mp.getModel(CollisionGraphRoadModel.class).getGraph();
        graphCrawler = new GraphCrawler(graph);
    }

}
