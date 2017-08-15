package nu.mine.mosher.gedcom;

import nu.mine.mosher.collection.TreeNode;
import nu.mine.mosher.gedcom.date.DatePeriod;
import nu.mine.mosher.gedcom.date.DateRange;
import nu.mine.mosher.gedcom.date.parser.GedcomDateValueParser;
import nu.mine.mosher.gedcom.date.parser.ParseException;
import nu.mine.mosher.gedcom.exception.InvalidLevel;
import nu.mine.mosher.gedcom.model.GedcomFile;
import nu.mine.mosher.gedcom.model.Loader;

import java.io.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Matches two GEDCOM files: OLD (e.g., previous version of my local GEDCOM file), and NEW
 * (e.g., latest version exported from Family Tree Maker, with changes from Ancestry.com).
 * Try to pull data from OLD file that is missing from NEW, and write out the updated NEW.
 */
class GedcomMatcher {
    public static void main(final String... args) throws InvalidLevel, IOException {
        if (args.length < 2) {
            throw new IllegalArgumentException("usage: java -jar gedcom-matcher.jar old.ged new.ged >out.ged");
        }

        final Loader oldLoad = loadGedcom(args[0]);
        final Loader newLoad = loadGedcom(args[1]);

        matchAndUpdate(oldLoad, newLoad);

        saveGedcom(newLoad);
        System.err.flush();
        System.out.flush();
    }

    private static Loader loadGedcom(final String filename) throws IOException, InvalidLevel {
        final File file = new File(filename);
        final Charset charset = Gedcom.getCharset(file);
        final GedcomTree gt = Gedcom.parseFile(file, charset);
        final Loader loader = new Loader(gt, filename);
        loader.parse();
        return loader;
    }

    private static void saveGedcom(final Loader load) throws IOException {
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(FileDescriptor.out), "UTF-8"));
        Gedcom.writeFile(load.getGedcom(), out, 120);
        out.flush();
        out.close();
    }

    private static void matchAndUpdate(final Loader oldLoad, final Loader newLoad) {
        repo(oldLoad, newLoad);
        sour(oldLoad, newLoad);
        indi(oldLoad, newLoad);
        obje(oldLoad, newLoad);

        if (setTitleDuplicates.size() > 0) {
            System.err.println("------------------------------------------------------------");
            System.err.println("WARNING: Duplicates found:");
            System.err.println(setTitleDuplicates);
        }

        remapIds(newLoad.getGedcom().getRoot());

        root(oldLoad, newLoad);

        date(oldLoad, newLoad);

        note(oldLoad, newLoad);
        quay(oldLoad, newLoad);
        sourApid(oldLoad, newLoad);
        mergeObjes(oldLoad, newLoad);
        addNewNodes();
    }

    private static void sourApid(Loader oldLoad, Loader newLoad) {
        System.err.println();
        System.err.println("------------------------------------------------------------");
        System.err.println("SOUR._APIDs");
        oldLoad.getGedcom().getRoot().forEach(oldSourNode -> {
            final GedcomLine oldSourLine = oldSourNode.getObject();
            if (oldSourLine.getTag().equals(GedcomTag.SOUR)) {
                final String apid = findChild(oldSourNode, "_APID");
                if (!apid.isEmpty()) {
                    final String newSourId = mapReverseIds.get(oldSourLine.getID());
                    if (newSourId == null) {
                        System.err.println("    NOT FOUND, for sour: "+oldSourLine.getID());
                    } else {
                        final TreeNode<GedcomLine> newSourNode = newLoad.getGedcom().getNode(newSourId);
                        assert newSourNode != null;
                        newSourNode.addChild(new TreeNode<>(new GedcomLine(1, "", "_APID", apid)));
                    }
                }
            }
        });
    }

    private static void mergeObjes(Loader oldLoad, Loader newLoad) {
        System.err.println();
        System.err.println("------------------------------------------------------------");
        System.err.println("OBJEs");
        oldLoad.getGedcom().getRoot().forEach(oldObjeNode -> {
            final GedcomLine oldObjeLine = oldObjeNode.getObject();
            if (oldObjeLine.getTag().equals(GedcomTag.OBJE)) {
                String id = oldObjeLine.getID();
                if (mapReverseIds.containsKey(id)) {
                    id = mapReverseIds.get(id);
                }
                final TreeNode<GedcomLine> newObjeNode = newLoad.getGedcom().getNode(id);
                if (newObjeNode == null) {
                    System.err.println("    NOT FOUND, for obje: "+oldObjeLine.getID());
                } else {
                    System.err.println("    found: "+newObjeNode.getObject());
                    final TreeNode<GedcomLine> newFileNode = findChildNode(newObjeNode, GedcomTag.FILE);
                    newObjeNode.addChildBefore(findChildNode(oldObjeNode, GedcomTag.FILE), newFileNode);
                }
            }
        });
    }

    private static void root(Loader oldLoad, Loader newLoad) {
        TreeNode<GedcomLine> headOld = null;
        {
            final Iterator<TreeNode<GedcomLine>> iTop = oldLoad.getGedcom().getRoot().children();
            while (iTop.hasNext() && headOld == null) {
                final TreeNode<GedcomLine> nodeTop = iTop.next();
                if (nodeTop.getObject().getTag().equals(GedcomTag.HEAD)) {
                    headOld = nodeTop;
                }
            }
        }
        if (headOld == null) {
            return;
        }

        String root = null;
        {
            final Iterator<TreeNode<GedcomLine>> iItem = headOld.children();
            while (iItem.hasNext() && root == null) {
                final TreeNode<GedcomLine> nodeItem = iItem.next();
                if (nodeItem.getObject().getTagString().equals("_ROOT")) {
                    root = nodeItem.getObject().getPointer();
                }
            }
        }
        if (root == null) {
            return;
        }

        TreeNode<GedcomLine> headNew = null;
        {
            final Iterator<TreeNode<GedcomLine>> iTop = newLoad.getGedcom().getRoot().children();
            while (iTop.hasNext() && headNew == null) {
                final TreeNode<GedcomLine> nodeTop = iTop.next();
                if (nodeTop.getObject().getTag().equals(GedcomTag.HEAD)) {
                    headNew = nodeTop;
                }
            }
        }
        if (headNew == null) {
            return;
        }

        {
            final Iterator<TreeNode<GedcomLine>> iItem = headNew.children();
            while (iItem.hasNext()) {
                final TreeNode<GedcomLine> nodeItem = iItem.next();
                final GedcomLine item = nodeItem.getObject();
                if (item.getTagString().equals("_ROOT")) {
                    nodeItem.setObject(new GedcomLine(item.getLevel(), "", "_ROOT", "@"+root+"@"));
                    return;
                }
            }
            newNodes.add(new ChildToBeAdded(headNew, new TreeNode<GedcomLine>(new GedcomLine(1, "", "_ROOT", "@"+root+"@"))));
        }

    }

    private static final Map<String, String> mapTitleToAncestryId = new HashMap<>(512);
    private static final Set<String> setTitleDuplicates = new HashSet<>(16);
    private static final Map<String, String> mapRemapIds = new HashMap<>(128);
    private static final Map<String, String> mapReverseIds = new HashMap<>(128);

    /*
    Some SOUR records are matched by _UID. If there is no _UID, then we try
    to match them from original.ged and reset the IDs to match.
    We only match on unique titles.
     */
    private static void sour(final Loader oldLoad, final Loader newLoad) {
        heuristicRestoreId(oldLoad, GedcomTag.SOUR, GedcomTag.TITL, newLoad);
    }

    private static void repo(final Loader oldLoad, final Loader newLoad) {
        heuristicRestoreId(oldLoad, GedcomTag.REPO, GedcomTag.NAME, newLoad);
    }

    /*
    We do INDIs the same as SOURces, matching on name.
    */
    private static void indi(final Loader oldLoad, final Loader newLoad) {
        heuristicRestoreIdIndis(oldLoad, newLoad);
    }

    // Also OBJE, matching on title/format
    private static void obje(final Loader oldLoad, final Loader newLoad) { heuristicRestoreIdObjes(oldLoad, newLoad); }

    private static void heuristicRestoreId(final Loader oldLoad, final GedcomTag tagRecord, final GedcomTag tagMatch, final Loader newLoad) {
        // build map of match-values to Ancestry IDs (but ignore duplicates)
        newLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine gedcomLine = top.getObject();
            if (gedcomLine.getTag().equals(tagRecord)) {
                if (findChild(top, GedcomTag.REFN).isEmpty()) {
                    final String title = findChild(top, tagMatch);
                    if (!setTitleDuplicates.contains(title)) {
                        if (mapTitleToAncestryId.containsKey(title)) {
                            mapTitleToAncestryId.remove(title);
                            setTitleDuplicates.add(title);
                        } else {
                            mapTitleToAncestryId.put(title, gedcomLine.getID());
                        }
                    }
                }
            }
        });

        /*
        check each original record to see if we can match it
        to an Ancestry record. If so, we will remap the Ancestry
        ID back to the Original ID.
         */
        oldLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine gedcomLine = top.getObject();
            if (gedcomLine.getTag().equals(tagRecord)) {
                if (findChild(top, GedcomTag.REFN).isEmpty()) {
                    final String title = findChild(top, tagMatch);
                    if (mapTitleToAncestryId.containsKey(title)) {
                        final String ancestryId = mapTitleToAncestryId.get(title);
                        final String originalId = gedcomLine.getID();
                        if (!ancestryId.equals(originalId)) {
                            mapRemapIds.put(ancestryId, originalId);
                            mapReverseIds.put(originalId, ancestryId);
                        }
                    }
                }
            }
        });
    }

    private static void heuristicRestoreIdIndis(final Loader oldLoad, final Loader newLoad) {
        // build map of match-values to Ancestry IDs (but ignore duplicates)
        newLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine gedcomLine = top.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.INDI)) {
                if (findChild(top, GedcomTag.REFN).isEmpty()) {
                    final String name = findChild(top, GedcomTag.NAME);
                    final String birthYear = getBirthYear(top);
                    final String title = name+"|"+birthYear;
                    if (!setTitleDuplicates.contains(title)) {
                        if (mapTitleToAncestryId.containsKey(title)) {
                            mapTitleToAncestryId.remove(title);
                            setTitleDuplicates.add(title);
                        } else {
                            final String id = gedcomLine.getID();
                            mapTitleToAncestryId.put(title, id);
                        }
                    }
                }
            }
        });

        /*
        check each original record to see if we can match it
        to an Ancestry record. If so, we will remap the Ancestry
        ID back to the Original ID.
         */
        oldLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine gedcomLine = top.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.INDI)) {
                if (findChild(top, GedcomTag.REFN).isEmpty()) {
                    final String name = findChild(top, GedcomTag.NAME);
                    final String birthYear = getBirthYear(top);
                    final String title = name+"|"+birthYear;
                    if (mapTitleToAncestryId.containsKey(title)) {
                        final String ancestryId = mapTitleToAncestryId.get(title);
                        final String originalId = gedcomLine.getID();
                        if (!ancestryId.equals(originalId)) {
                            mapRemapIds.put(ancestryId, originalId);
                            mapReverseIds.put(originalId, ancestryId);
                        }
                    } else {
                        System.err.println("WARNING: Cannot match INDI based on name|birthyear: "+title);
                    }
                }
            }
        });
    }

    private static void heuristicRestoreIdObjes(final Loader oldLoad, final Loader newLoad) {
        final Map<String, String> mapNewObjeToIndi = new HashMap<>();
        newLoad.getGedcom().getRoot().forEach(indi-> {
            final GedcomLine gedcomLine = indi.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.INDI)) {
                final String name = findChild(indi, GedcomTag.NAME);
                for (final TreeNode<GedcomLine> c : indi) {
                    final GedcomLine cLine = c.getObject();
                    if (cLine.getTag().equals(GedcomTag.OBJE)) {
                        // don't bother checking for dups, this is heuristic only
                        mapNewObjeToIndi.put(cLine.getPointer(), name);
                    }
                }
            }
        });

        // build map of match-values to Ancestry IDs (but ignore duplicates)
        newLoad.getGedcom().getRoot().forEach(obje -> {
            final GedcomLine gedcomLine = obje.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.OBJE)) {
                if (findChild(obje, GedcomTag.REFN).isEmpty()) {
                    for (final TreeNode<GedcomLine> c : obje) {
                        if (c.getObject().getTag().equals(GedcomTag.FILE)) {
                            final String title = findChild(c, GedcomTag.TITL);
                            final String usedBy = mapNewObjeToIndi.get(gedcomLine.getID());
                            final String match = title+"|"+usedBy;
                            if (!setTitleDuplicates.contains(match)) {
                                if (mapTitleToAncestryId.containsKey(match)) {
                                    mapTitleToAncestryId.remove(match);
                                    setTitleDuplicates.add(match);
                                } else {
                                    final String id = gedcomLine.getID();
                                    mapTitleToAncestryId.put(match, id);
                                }
                            }
                        }
                    }
                }
            }
        });

        final Map<String, String> mapOldObjeToIndi = new HashMap<>();
        oldLoad.getGedcom().getRoot().forEach(indi-> {
            final GedcomLine gedcomLine = indi.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.INDI)) {
                final String name = findChild(indi, GedcomTag.NAME);
                for (final TreeNode<GedcomLine> c : indi) {
                    final GedcomLine cLine = c.getObject();
                    if (cLine.getTag().equals(GedcomTag.OBJE)) {
                        // don't bother checking for dups, this is heuristic only
                        mapOldObjeToIndi.put(cLine.getPointer(), name);
                    }
                }
            }
        });
        /*
        check each original record to see if we can match it
        to an Ancestry record. If so, we will remap the Ancestry
        ID back to the Original ID.
         */
        oldLoad.getGedcom().getRoot().forEach(obje -> {
            final GedcomLine gedcomLine = obje.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.OBJE)) {
                if (findChild(obje, GedcomTag.REFN).isEmpty()) {
                    for (final TreeNode<GedcomLine> c : obje) {
                        if (c.getObject().getTag().equals(GedcomTag.FILE)) {
                            final String title55 = findChild(c, GedcomTag.TITL);
                            final String usedBy = mapOldObjeToIndi.get(gedcomLine.getID());
                            final String match = title55 + "|" + usedBy;
                            if (mapTitleToAncestryId.containsKey(match)) {
                                final String ancestryId = mapTitleToAncestryId.get(match);
                                final String originalId = gedcomLine.getID();
                                if (!ancestryId.equals(originalId)) {
                                    mapRemapIds.put(ancestryId, originalId);
                                    mapReverseIds.put(originalId, ancestryId);
                                }
                            } else {
                                System.err.println("WARNING: Cannot match OBJE based on title|person: " + match);
                            }
                        }
                    }
                }
            }
        });
    }

    private static void remapIds(final TreeNode<GedcomLine> node) {
        node.forEach(c -> remapIds(c));

        final GedcomLine gedcomLine = node.getObject();
        if (gedcomLine != null) {
            if (gedcomLine.hasID()) {
                final String newId = mapRemapIds.get(gedcomLine.getID());
                if (newId != null) {
                    node.setObject(new GedcomLine(gedcomLine.getLevel(), "@"+newId+"@", gedcomLine.getTag().name(), gedcomLine.getValue()));
                }
            }
            if (gedcomLine.isPointer()) {
                final String newId = mapRemapIds.get(gedcomLine.getPointer());
                if (newId != null) {
                    // assume that no line with a pointer also has an ID (true as of Gedcom 5.5)
                    node.setObject(new GedcomLine(gedcomLine.getLevel(), "", gedcomLine.getTag().name(), "@"+newId+"@"));
                }
            }
            /* clear out all RINs in the generated file */
            if (gedcomLine.getTag().equals(GedcomTag.RIN)) {
                node.setObject(new GedcomLine(gedcomLine.getLevel(), "", gedcomLine.getTag().name(), ""));
            }
        }
    }

    /*
    On import, Ancestry converts FROM-TO DATE records to ranges (BET style).
    This tries to match them to the dates from original.ged and converts them
    back again.
     */
    private static void date(final Loader oldLoad, final Loader newLoad) {
        final Set<GedcomTag> tagsIndi = new HashSet<>(GedcomTag.setIndividualAttribute);
        tagsIndi.addAll(GedcomTag.setIndividualEvent);
        final Set<GedcomTag> tagsFam = GedcomTag.setFamilyEvent;

        System.err.println("------------------------------------------------------------");
        System.err.println("Dates");
        oldLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine gedcomLine = top.getObject();
            final GedcomTag tag = gedcomLine.getTag();
            if (tag.equals(GedcomTag.INDI)) {
                dateRangeToPeriodFor(top, oldLoad, newLoad, tagsIndi);
            } else if (tag.equals(GedcomTag.FAM)) {
                dateRangeToPeriodFor(top, oldLoad, newLoad, tagsFam);
            }
        });
    }

    private static void dateRangeToPeriodFor(final TreeNode<GedcomLine> top, final Loader oldLoad, final Loader newLoad, final Set<GedcomTag> tagsEvents) {
        top.forEach(event -> {
            if (tagsEvents.contains(event.getObject().getTag())) {
                final TreeNode<GedcomLine> d = findDate(event);
                if (d != null) {
                    final String ds = d.getObject().getValue();
                    if (ds.startsWith("FROM ") || ds.startsWith("TO ")) {
                        final String dsWant = cvtRangeToPeriod(ds);
                        System.err.println("dateRangeToPeriodFor: " + top.getObject() + " | " + event + " | " + d + " | looking for: " + dsWant);
                        boolean found = false;
                        final TreeNode<GedcomLine> nodeNewTop = newLoad.getGedcom().getNode(top.getObject().getID());
                        if (nodeNewTop != null) {
                            System.err.println("    searching for new event under: " + nodeNewTop);
                            for (final TreeNode<GedcomLine> newEvent : nodeNewTop) {
                                final GedcomLine newEventGedcomLine = newEvent.getObject();
                                if (newEventGedcomLine.getTag().equals(event.getObject().getTag())) {
                                    final TreeNode<GedcomLine> newD = findDate(newEvent);
                                    if (newD != null) {
                                        System.err.println("    checking " + newD);
                                        final GedcomLine newDateGedcomLine = newD.getObject();
                                        if (newDateGedcomLine.getValue().equals(dsWant)) {
                                            System.err.println("    found: " + newD);
                                            newD.setObject(new GedcomLine(newDateGedcomLine.getLevel(), "", newDateGedcomLine.getTag().name(), ds));
                                            System.err.println("    chngd: " + newD);
                                            found = true;
                                            break;
                                        } else if (newDateGedcomLine.getValue().equals(ds)) {
                                            // already in correct format; don't change anything
                                            found = true;
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                        if (!found) {
                            System.err.println("    NOT FOUND, for date: " + top.getObject() + " | " + event + " | " + d + " | looking for: " + dsWant);
                        }
                    }
                }
            }
        });
    }

    private static final Pattern DATE_FROMTO = Pattern.compile("FROM (.*) TO (.*)");
    private static final Pattern DATE_FROM = Pattern.compile("FROM (.*)");
    private static final Pattern DATE_TO = Pattern.compile("TO (.*)");

    private static String cvtRangeToPeriod(final String dsRange) {
        Matcher matcher;
        if ((matcher = DATE_FROMTO.matcher(dsRange)).matches()) {
            return "BET " + matcher.group(1) + " AND " + matcher.group(2);
        } else if ((matcher = DATE_FROM.matcher(dsRange)).matches()) {
            return "AFT " + matcher.group(1);
        } else if ((matcher = DATE_TO.matcher(dsRange)).matches()) {
            return "BEF " + matcher.group(1);
        } else {
            System.err.println("Unexpected date format: " + dsRange);
            return "ERROR";
        }
    }

    private static final Pattern DATE_BET = Pattern.compile("BET (.*) AND (.*)");
    private static final Pattern DATE_BEF = Pattern.compile("BEF (.*)");
    private static final Pattern DATE_AFT = Pattern.compile("AFT (.*)");

    private static String cvtPeriodToRange(final String dsPeriod) {
        Matcher matcher;
        if ((matcher = DATE_BET.matcher(dsPeriod)).matches()) {
            return "FROM " + matcher.group(1) + " TO " + matcher.group(2);
        } else if ((matcher = DATE_AFT.matcher(dsPeriod)).matches()) {
            return "FROM " + matcher.group(1);
        } else if ((matcher = DATE_BEF.matcher(dsPeriod)).matches()) {
            return "TO " + matcher.group(1);
        } else {
            System.err.println("Unexpected date format: " + dsPeriod);
            return "ERROR";
        }
    }

    private static TreeNode<GedcomLine> findDate(final TreeNode<GedcomLine> event) {
        for (final TreeNode<GedcomLine> c : event) {
            if (c.getObject().getTag().equals(GedcomTag.DATE)) {
                return c;
            }
        }
        return null;
    }

    /*
    Ancestry lost all top-level NOTE records. Restore them from original.ged by
    matching up the event it's attached to with the corresponding event in ancestry.ged.
    Keep track of failed matches, and matches that are ambiguous. Ambiguous matches
    simply attach the NOTE to the *first* matched event.
     */
    private static void note(final Loader oldLoad, final Loader newLoad) {
        System.err.println();
        System.err.println("------------------------------------------------------------");
        System.err.println("Notes");
        oldLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine topLine = top.getObject();
            top.forEach(item -> {
                item.forEach(att -> {
                    final GedcomLine attLine = att.getObject();
                    if (attLine.getTag().equals(GedcomTag.NOTE)) {
                        final TreeNode<GedcomLine> noteNode = oldLoad.getGedcom().getNode(attLine.getPointer());
                        if (noteNode != null) {
                            addNoteTo(top, item, att, noteNode, newLoad);
                        }
                    }
                });
            });
        });
    }

    private static void addNoteTo(TreeNode<GedcomLine> top, TreeNode<GedcomLine> item, TreeNode<GedcomLine> oldNoteRef, TreeNode<GedcomLine> noteNode, Loader newLoad) {
        String id = top.getObject().getID();
        if (mapReverseIds.containsKey(id)) {
            id = mapReverseIds.get(id);
        }
        System.err.println("looking for: " + id + ": " + top.getObject() + " | " + item.getObject().getTag());
        final TreeNode<GedcomLine> topNew = newLoad.getGedcom().getNode(id);
        int cFound = 0;
        if (topNew != null) {
            for (final TreeNode<GedcomLine> itemNew : topNew) {
                System.err.println("    checking:" + itemNew);
                if (itemsMatch(item, itemNew, topNew)) {
                    final String type = findChild(itemNew, GedcomTag.TYPE);
                    final String date = findChild(itemNew, GedcomTag.DATE);
                    final String place = findChild(itemNew, GedcomTag.PLAC);
                    System.err.println("    found:" + itemNew + " "+ type + " " + date + " " + place);
                    ++cFound;
                    if (cFound == 1) {
                        newNodes.add(new ChildToBeAdded(itemNew, new TreeNode<GedcomLine>(oldNoteRef.getObject())));
                        newNodes.add(new ChildToBeAdded(newLoad.getGedcom().getRoot(), noteNode));
                    }
                }
            }
        }
        if (cFound == 0) {
            System.err.println("    NOT FOUND, for note: " + top.getObject() + " | " + item.getObject().getTag() + " | " + noteNode.getObject());
        } else if (cFound > 1) {
            System.err.println("    MULTIPLE MATCHING EVENTS FOUND, for note: " + top.getObject() + " | " + item.getObject().getTag() + " | " + noteNode.getObject());
        }
    }

    // TODO: remove param topNew and use itemNew.getParent instead
    private static boolean itemsMatch(TreeNode<GedcomLine> item, TreeNode<GedcomLine> itemNew, TreeNode<GedcomLine> topNew) {
        final GedcomLine itemLine = item.getObject();
        final GedcomLine itemLineNew = itemNew.getObject();

        if (itemLine == null || itemLineNew == null) {
            return false;
        }

        if (!itemLine.getTag().equals(itemLineNew.getTag())) {
            return false;
        }

        String type = "";
        if (itemLine.getTag().equals(GedcomTag.EVEN)) {
            // check TYPEs of generic EVEN items
            type = findChild(item, GedcomTag.TYPE).toLowerCase();
            final String typeNew = findChild(itemNew, GedcomTag.TYPE).toLowerCase();
            if (!type.equals(typeNew)) {
                return false;
            }
        }

        if (isUnique(itemLine.getTag(), type, topNew)) {
            return true;
        }

        final String val = itemLine.getValue();
        final String valNew = itemLineNew.getValue();
        if (!val.equals(valNew)) {
            return false;
        }

        final String date = findChild(item, GedcomTag.DATE);
        final String dateNew = findChild(itemNew, GedcomTag.DATE);
        if (!date.isEmpty() && !dateNew.isEmpty()) {
            return date.equals(dateNew);
        }
        if (!date.isEmpty() || !dateNew.isEmpty()) {
            return false;
        }

        final String place = findChild(item, GedcomTag.PLAC).split(",")[0].toLowerCase();
        final String placeNew = findChild(itemNew, GedcomTag.PLAC).split(",")[0].toLowerCase();
        if (!place.equals(placeNew)) {
            return false;
        }

        final String source = findChild(item, GedcomTag.SOUR);
        final String sourceNew = findChild(itemNew, GedcomTag.SOUR);
        if (!source.isEmpty() && !sourceNew.isEmpty() && source.equals(sourceNew)) {
            return true;
        }

        return false;
    }

    private static boolean isUnique(final GedcomTag tag, final String even, final TreeNode<GedcomLine> obj) {
        int cTag = 0;
        for (final TreeNode<GedcomLine> c : obj) {
            if (tag.equals(GedcomTag.EVEN)) {
                final String typeNew = findChild(c, GedcomTag.TYPE).toLowerCase();
                if (even.equals(typeNew)) {
                    ++cTag;
                    if (cTag > 1) {
                        return false;
                    }
                }
            } else if (c.getObject().getTag().equals(tag)) {
                ++cTag;
                if (cTag > 1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static String findChild(final TreeNode<GedcomLine> item, final GedcomTag tag) {
        return findChild(item, tag.toString());
    }

    private static String findChild(final TreeNode<GedcomLine> item, final String tag) {
        for (final TreeNode<GedcomLine> c : item) {
            final GedcomLine gedcomLine = c.getObject();
            if (gedcomLine.getTagString().equals(tag)) {
                return gedcomLine.isPointer() ? gedcomLine.getPointer() : gedcomLine.getValue();
            }
        }
        return "";
    }

    private static TreeNode<GedcomLine> findChildNode(final TreeNode<GedcomLine> item, final GedcomTag tag) {
        for (final TreeNode<GedcomLine> c : item) {
            final GedcomLine gedcomLine = c.getObject();
            if (gedcomLine.getTag().equals(tag)) {
                return c;
            }
        }
        return null;
    }

    private static String getBirthYear(final TreeNode<GedcomLine> nodeIndi) {
        String year = "";
        for (final TreeNode<GedcomLine> c : nodeIndi) {
            final GedcomLine gedcomLine = c.getObject();
            if (gedcomLine.getTag().equals(GedcomTag.BIRT)) {
                final String fullDate = findChild(c, GedcomTag.DATE);
                if (!fullDate.isEmpty()) {
                    try {
                        final DatePeriod d = parse(fullDate);
                        year = Integer.toString(d.getStartDate().getEarliest().getYear());
                    } catch (final Throwable ignore) {
                        year = "";
                    }
                }
            }
        }
        return year;
    }

    private static DatePeriod parse(final String s) throws ParseException, DateRange.DatesOutOfOrder
    {
        final GedcomDateValueParser parser = new GedcomDateValueParser(
                new StringReader(s));

        return parser.parse();
    }
//    private static String findChild(final TreeNode<GedcomLine> item, final String tag) {
//        for (final TreeNode<GedcomLine> c : item) {
//            final GedcomLine gedcomLine = c.getObject();
//            if (gedcomLine.getTagString().equals(tag)) {
//                return gedcomLine.isPointer() ? gedcomLine.getPointer() : gedcomLine.getValue();
//            }
//        }
//        return "";
//    }

    /*
    All QUAY records are lost by Ancestry. Try to restore them from
    original.ged.
     */
    private static void quay(final Loader oldLoad, final Loader newLoad) {
        System.err.println();
        System.err.println("------------------------------------------------------------");
        System.err.println("Quality / _APID");
        oldLoad.getGedcom().getRoot().forEach(top -> {
            final GedcomLine topLine = top.getObject();
            top.forEach(item -> {
                item.forEach(att -> {
                    final GedcomLine attLine = att.getObject();
                    if (attLine.getTag().equals(GedcomTag.SOUR)) {
                        addQuayTo(top, item, att, newLoad);
                    }
                });
            });
        });
    }

    private static void addQuayTo(TreeNode<GedcomLine> top, TreeNode<GedcomLine> item, TreeNode<GedcomLine> att, Loader newLoad) {
        /*
            ORIGINAL oldLoad
            --------
             0 @I12@ INDI      <-----------------------------top
               1 NAME Alice Irene /Harrison/
               1 GRAD          <-----------------------------item
                 2 DATE JUN 1925
                 2 PLAC Corning Northside High School, Corning, Steuben, New York, U
                   3 CONC SA
                 2 SOUR @S87@  <-----------------------------att
            -      3 QUAY 3    <-----------------------------quay
                 2 NOTE @T12@
               1 NAME Margaret /Alred/ <---------------------item
                 2 SOUR @S722@ <-----------------------------att
            -      3 QUAY 1



            ANCESTRY newLoad
            --------
             0 @I12@ INDI      <-----------------------------topNew
               1 NAME Alice Irene /Harrison/
               1 GRAD          <-----------------------------itemNew
                 2 DATE JUN 1925
                 2 PLAC Corning Northside High School, Corning, Steuben, New York, U
                   3 CONC SA
                 2 SOUR @S87@  <-----------------------------attNew
                   3 QUAY 3   ++++++++++++++++++++++++++++
        */
        TreeNode<GedcomLine> quay = null;
        for (final TreeNode<GedcomLine> q : att) {
            if (q.getObject().getTag().equals(GedcomTag.QUAY)) {
                quay = q;
                break;
            }
        }
        TreeNode<GedcomLine> apid = null;
        for (final TreeNode<GedcomLine> q : att) {
            if (q.getObject().getTagString().equals("_APID")) {
                apid = q;
                break;
            }
        }
        if (quay == null && apid == null) {
            return;
        }

        String id = top.getObject().getID();
        if (mapReverseIds.containsKey(id)) {
            id = mapReverseIds.get(id);
        }
        System.err.println("looking for: " + id + ": " + top.getObject() + " | " + item.getObject().getTag() + " | " + att.getObject());
        final TreeNode<GedcomLine> topNew = newLoad.getGedcom().getNode(id);
        int cFound = 0;
        if (topNew != null) {
            for (final TreeNode<GedcomLine> itemNew : topNew) {
                System.err.println("    checking:" + itemNew);
                if (itemsMatch(item, itemNew, topNew)) {
                    for (final TreeNode<GedcomLine> attNew : itemNew) {
                        final GedcomLine gedcomLine = attNew.getObject();
                        String newId = gedcomLine.getPointer();
                        if (mapRemapIds.containsKey(newId)) {
                            newId = mapRemapIds.get(newId);
                        }
                        if (gedcomLine.getTag().equals(GedcomTag.SOUR) && newId.equals(att.getObject().getPointer())) {
                            System.err.println("    found:" + attNew);
                            ++cFound;
                            if (cFound == 1) {
                                if (quay != null) {
                                    newNodes.add(new ChildToBeAdded(attNew, quay));
                                }
                                if (apid != null) {
                                    newNodes.add(new ChildToBeAdded(attNew, apid));
                                }
                            }
                        }
                    }
                }
            }
        }
        if (cFound == 0) {
            System.err.println("    NOT FOUND, for "+(quay!=null?"quay":"")+","+(apid!=null?"apid":"")+": " + top.getObject() + " | " + item.getObject().getTag() + " | " + att.getObject());
        } else if (cFound > 1) {
            System.err.println("    MULTIPLE MATCHING EVENTS FOUND, for  "+(quay!=null?"quay":"")+","+(apid!=null?"apid":"")+": " + top.getObject() + " | " + item.getObject().getTag() + " | " + att.getObject());
        }
    }

    static class ChildToBeAdded {
        TreeNode<GedcomLine> parent;
        TreeNode<GedcomLine> child;
        TreeNode<GedcomLine> before;
        ChildToBeAdded(TreeNode<GedcomLine> parent, TreeNode<GedcomLine> child) {
            this(parent, child, null);
        }
        ChildToBeAdded(TreeNode<GedcomLine> parent, TreeNode<GedcomLine> child, TreeNode<GedcomLine> before) {
            this.parent = parent; this.child = child; this.before = before;
        }
    }
    private static final List<ChildToBeAdded> newNodes = new ArrayList<>(256);
    private static void addNewNodes() {
        newNodes.forEach(a -> {
            if (a.before == null) {
                a.parent.addChild(a.child);
            } else {
                a.parent.addChildBefore(a.child, a.before);
            }
        });
    }
}
