/*
 * (c) Copyright 2008, 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 * [See end of file]
 */

package com.hp.hpl.jena.tdb.store;

import static com.hp.hpl.jena.sparql.core.Quad.isDefaultGraph;
import static com.hp.hpl.jena.sparql.core.Quad.isQuadUnionGraph;

import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import atlas.iterator.Iter;
import atlas.lib.Tuple;

import com.hp.hpl.jena.graph.Node;
import com.hp.hpl.jena.graph.Triple;
import com.hp.hpl.jena.graph.TripleMatch;
import com.hp.hpl.jena.shared.PrefixMapping;
import com.hp.hpl.jena.sparql.core.Quad;
import com.hp.hpl.jena.tdb.TDBException;
import com.hp.hpl.jena.tdb.graph.GraphSyncListener;
import com.hp.hpl.jena.tdb.graph.UpdateListener;
import com.hp.hpl.jena.tdb.nodetable.NodeTupleTable;
import com.hp.hpl.jena.tdb.solver.reorder.ReorderTransformation;
import com.hp.hpl.jena.tdb.sys.SystemTDB;
import com.hp.hpl.jena.util.iterator.ExtendedIterator;

/** A graph implementation that projects a graph from a quad table */
public class GraphNamedTDB extends GraphTDBBase
{
    /*
        Quad.unionGraph
        Quad.defaultGraphIRI
        Quad.defaultGraphNodeGenerated
    */
    private static Logger log = LoggerFactory.getLogger(GraphNamedTDB.class) ;
    
    private final QuadTable quadTable ; 
    private NodeId graphNodeId = null ;

    private final ReorderTransformation transform ;

    public GraphNamedTDB(DatasetGraphTDB dataset, Node graphName, ReorderTransformation transform) 
    {
        super(dataset, graphName, transform, dataset.getLocation()) ;
        this.quadTable = dataset.getQuadTable() ;
        this.transform = transform ;
        
        if ( graphName == null )
            throw new TDBException("GraphNamedTDB: Null graph name") ; 
        if ( ! graphName.isURI() )
            throw new TDBException("GraphNamedTDB: Graph name not a URI") ; 
        
        int syncPoint = SystemTDB.SyncTick ;
        if ( syncPoint > 0 )
            this.getEventManager().register(new GraphSyncListener(this, syncPoint)) ;
        this.getEventManager().register(new UpdateListener(this)) ;
    }

//    @Override
//    public QueryHandler queryHandler()
//    { return queryHandler ; }
//    
//    @Override
//    public TransactionHandler getTransactionHandler()
//    { return transactionHandler ; }
    
    @Override
    protected PrefixMapping createPrefixMapping()
    {
        return dataset.getPrefixes().getPrefixMapping(graphNode.getURI()) ;
    }

    @Override
    public void performAdd( Triple t ) 
    { 
        if ( isQuadUnionGraph(graphNode) )
            throw new TDBException("Can't add a triple to the RDF merge of all named graphs") ;
        boolean changed ;
        if ( isDefaultGraph(graphNode) )
            changed = dataset.getTripleTable().add(t) ;
            //throw new TDBException("Attempt to add a triple to the default graph via its named form");
        else 
            changed = dataset.getQuadTable().add(graphNode, t) ;
        
        if ( ! changed )
            duplicate(t) ;
    }

 
    @Override
    public void performDelete( Triple t ) 
    { 
        if ( isQuadUnionGraph(graphNode) )
            throw new TDBException("Can't delete triple from the RDF merge of all named graphs") ;
        boolean changed ;
        if ( isDefaultGraph(graphNode) )
            changed = dataset.getTripleTable().delete(t) ;
            //throw new TDBException("Attempt to delete a triple from the default graph via its named form"); 
        else 
            changed = dataset.getQuadTable().delete(graphNode, t) ;
    }
    
    @Override
    protected ExtendedIterator<Triple> graphBaseFind(TripleMatch m)
    {
        if ( isDefaultGraph(graphNode) )
            // Default graph.
            return graphBaseFindWorker(getDataset().getTripleTable(), m) ;

        Node gn = graphNode ;
        if ( isQuadUnionGraph(graphNode) )
            gn = Node.ANY ;
        
        Iterator<Quad> iter = dataset.getQuadTable().find(gn, m.getMatchSubject(), m.getMatchPredicate(), m.getMatchObject()) ;
        if ( iter == null )
            return com.hp.hpl.jena.util.iterator.NullIterator.instance() ;
        
        Iterator<Triple> iterTriples = new ProjectQuadsToTriples((gn == Node.ANY ? null : gn) , iter) ;
        
        if ( gn == Node.ANY )
            iterTriples = Iter.distinct(iterTriples) ;
        return new MapperIteratorTriples(iterTriples) ;

    }

    @Override
    protected Iterator<Tuple<NodeId>> countThis()
    {
        NodeId gn = getGraphNodeId() ;
        Tuple<NodeId> t = Tuple.create(gn, null, null, null) ;
//        TDB.sync(this) ;
//        
        Iterator<Tuple<NodeId>> iter = dataset.getQuadTable().getNodeTupleTable().getTupleTable().find(t) ;
//        Iterator<Tuple<NodeId>> iter = dataset.getQuadTable().getNodeTupleTable().getTupleTable().getIndex(0).find(t) ;
        return iter ;
    }
    
    /** Graph node as NodeId */
    public final NodeId getGraphNodeId()
    {
        if ( graphNodeId == null )
            graphNodeId = dataset.getQuadTable().getNodeTupleTable().getNodeTable().getNodeIdForNode(graphNode) ;
        return graphNodeId ;
    }

    @Override
    public Tuple<Node> asTuple(Triple triple)
    {
//        if ( getGraphNode() == null )
//            return Tuple.create(triple.getSubject(), triple.getPredicate(), triple.getObject()) ;
//        else
            return Tuple.create(getGraphNode(), triple.getSubject(), triple.getPredicate(), triple.getObject()) ;
    }

    @Override
    protected final Logger getLog() { return log ; }
    
    @Override
    public NodeTupleTable getNodeTupleTable()
    {
        // Concrete default graph.
        if ( graphNode == null || Quad.isDefaultGraph(graphNode) )
            return dataset.getTripleTable().getNodeTupleTable() ;
        return dataset.getQuadTable().getNodeTupleTable() ;
    }

    @Override
    final public void close()
    { 
        // Do nothing.  May be retuned via the dataset again later. 
    }
    
    @Override
    public void sync(boolean force)
    {
        dataset.sync(force);
    }
}

/*
 * (c) Copyright 2008, 2009 Hewlett-Packard Development Company, LP
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. The name of the author may not be used to endorse or promote products
 *    derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHOR ``AS IS'' AND ANY EXPRESS OR
 * IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
 * IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
 * DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
 * THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF
 * THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */