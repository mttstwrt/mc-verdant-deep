package dev.syrval.verdantdeep.ravine;

/**
 * One disc of a hole as an audit sees it (see {@code AbyssAuditCommand}): its place in the hole's layout, the middle of its
 * platform's top, its radius, its theme's label (see {@link ChasmStats}) and what holds it up.
 */
public record DiscSummary(double holeX, double holeZ, int index, double x, double z, double floor, double radius, String theme, String support) {}
