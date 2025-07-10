package org.sensorhub.ui.table;

import com.vaadin.data.validator.IntegerRangeValidator;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vaadin.v7.data.Container;
import com.vaadin.v7.ui.Table;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


public class PagedTable extends Table {
    private static final long serialVersionUID = 6881455780158545828L;

    public interface PageChangeListener {
        public void pageChanged(PagedTableChangeEvent event);
    }

    public class PagedTableChangeEvent {

        final PagedTable table;

        public PagedTableChangeEvent(PagedTable table) {
            this.table = table;
        }

        public PagedTable getTable() {
            return table;
        }

        public int getCurrentPage() {
            return table.getCurrentPage();
        }

        public int getTotalAmountOfPages() {
            return table.getTotalAmountOfPages();
        }
    }

    private List<PageChangeListener> listeners = null;

    private PagedTableContainer container;


    public PagedTable() {
        this(null);
    }

    public PagedTable(String caption) {
        super(caption);
        setPageLength(25);
        addStyleName("pagedtable");
    }

    public PagedTableControls createControls() {
        return new PagedTableControls(this);
    }

    @Override
    public Container.Indexed getContainerDataSource() {
        return container;
    }

    @Override
    public void setContainerDataSource(Container newDataSource) {
        setContainerDataSource(newDataSource, null);
    }

    @Override
    public void setContainerDataSource(Container newDataSource, Collection<?> visibleIds) {
        if (!(newDataSource instanceof Container.Indexed)) {
            throw new IllegalArgumentException(
                    "PagedTable can only use containers that implement Container.Indexed");
        }
        PagedTableContainer pagedTableContainer = new PagedTableContainer(
                (Container.Indexed) newDataSource);
        pagedTableContainer.setPageLength(getPageLength());
        if (visibleIds!=null) {
            super.setContainerDataSource(pagedTableContainer, visibleIds);
        } else {
            super.setContainerDataSource(pagedTableContainer);
        }
        this.container = pagedTableContainer;
        firePagedChangedEvent();
    }

    private void setPageFirstIndex(int firstIndex) {
        if (container != null) {
            if (firstIndex <= 0) {
                firstIndex = 0;
            }
            if (firstIndex > container.getRealSize() - 1) {
                int size = container.getRealSize() - 1;
                int pages = 0;
                if (getPageLength() != 0) {
                    pages = (int) Math.floor((double)size / getPageLength());
                }
                firstIndex = pages * getPageLength();
            }
            
            container.setStartIndex(firstIndex);
            // this was causing redundant call of getItemIds(start, end)
            // and it is not needed because this method is called by fired page changed event
            //setCurrentPageFirstItemIndex(firstIndex);
            containerItemSetChange(new Container.ItemSetChangeEvent() {
                private static final long serialVersionUID = -5083660879306951876L;

                public Container getContainer() {
                    return container;
                }
            });
            if (alwaysRecalculateColumnWidths) {
                for (Object columnId : container.getContainerPropertyIds()) {
                    setColumnWidth(columnId, -1);
                }
            }
            //firePagedChangedEvent(); // already called in containerItemSetChange
        }
    }

    protected void firePagedChangedEvent() {
        if (listeners != null) {
            PagedTableChangeEvent event = new PagedTableChangeEvent(this);
            for (PageChangeListener listener : listeners) {
                listener.pageChanged(event);
            }
        }
    }

    @Override
    public void containerItemSetChange(Container.ItemSetChangeEvent event) {
        super.containerItemSetChange(event);
        firePagedChangedEvent();
    }

    @Override
    public void setPageLength(int pageLength) {
        if (pageLength >= 0 && getPageLength() != pageLength) {
            container.setPageLength(pageLength);
            super.setPageLength(pageLength);
            firePagedChangedEvent();
        }
    }

    public void nextPage() {
        setPageFirstIndex(container.getStartIndex() + getPageLength());
    }

    public void previousPage() {
        setPageFirstIndex(container.getStartIndex() - getPageLength());
    }

    public int getCurrentPage() {
        double pageLength = getPageLength();
        int page = (int) Math.floor((double) container.getStartIndex()
                / pageLength) + 1;
        if (page < 1) {
            page = 1;
        }
        return page;
    }

    public void setCurrentPage(int page) {
        int newIndex = (page - 1) * getPageLength();
        if (newIndex < 0) {
            newIndex = 0;
        }
        if (newIndex >= 0 && newIndex != container.getStartIndex()) {
            setPageFirstIndex(newIndex);
        }
    }

    public int getTotalAmountOfPages() {
        int size = container.getContainer().size();
        double pageLength = getPageLength();
        int pageCount = (int) Math.ceil(size / pageLength);
        if (pageCount < 1) {
            pageCount = 1;
        }
        return pageCount;
    }

    public void addListener(PageChangeListener listener) {
        if (listeners == null) {
            listeners = new ArrayList<PageChangeListener>();
        }
        listeners.add(listener);
    }

    public void removeListener(PageChangeListener listener) {
        if (listeners == null) {
            listeners = new ArrayList<PageChangeListener>();
        }
        listeners.remove(listener);
    }

    public void setAlwaysRecalculateColumnWidths(
            boolean alwaysRecalculateColumnWidths) {
        this.alwaysRecalculateColumnWidths = alwaysRecalculateColumnWidths;
    }

}