import { useEffect, useRef, useState } from 'react';
import {
  ArrowRight,
  BarChart,
  Book,
  CheckCircle,
  ChevronRight,
  Code,
  Database,
  Globe,
  MessageSquare,
  PenLine,
  Scale,
  Search,
  Send,
  Sparkles,
  RefreshCw,
  Star,
  Users,
  X,
} from 'lucide-react';
import { Link, useLocation, useNavigate } from 'react-router-dom';
import searchHistoryService from '../../api/searchHistoryService';
import { useAuth } from '../../contexts/AuthContext';
import { Badge, Button, StarRating } from '../../components/ui';
import publicationsService from '../../api/publicationsService';
import categoriesService from '../../api/categoriesService';
import recommendationService, { RecommendedPublication } from '../../api/recommendationService';
import systemReviewsService, { SystemReview } from '../../api/systemReviewsService';
import {
  Category,
  MostBorrowedPublication,
  NewestPublication,
  PublicLibraryStats,
} from '../../api/publicationTypes';
import { useTranslation } from '../../contexts/LanguageContext';
import { formatReviewerAcademicLine } from '../../utils/reviewerMeta';

const BOOK_COVER_PLACEHOLDER = '/books/book-placeholder.svg';
const usePublicPrefix = () => {
  const { pathname } = useLocation();
  return pathname.startsWith('/librarianpage/public') ? '/librarianpage/public' : '/publicpage';
};

const HeroSection = ({ stats }: { stats: PublicLibraryStats }) => {
  const navigate = useNavigate();
  const { userType } = useAuth();
  const { language, t } = useTranslation();
  const publicPrefix = usePublicPrefix();
  const [searchInput, setSearchInput] = useState('');
  const [history, setHistory] = useState<import('../../api/searchHistoryService').SearchHistoryItem[]>([]);

  useEffect(() => {
    if (!userType) return;
    searchHistoryService.getHistory()
      .then(res => setHistory(res.data ?? []))
      .catch(() => {});
  }, [userType]);

  const handleSearch = (kw?: string) => {
    const q = (kw ?? searchInput).trim();
    navigate(q ? `${publicPrefix}/search?q=${encodeURIComponent(q)}` : `${publicPrefix}/search`);
  };

  const handleDeleteHistory = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    setHistory(prev => prev.filter(h => h.id !== id));
    searchHistoryService.deleteHistory(id).catch(() => {});
  };

  return (
    <div className="bg-gradient-to-r from-blue-50 to-indigo-50 relative overflow-hidden">
      {/* Background decorations */}
      <div className="absolute top-0 right-0 w-1/2 h-full bg-blue-100/50 rounded-bl-[100px] -z-10"></div>

      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-10 sm:py-14 lg:py-24 relative z-10">
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-8 lg:gap-12 items-center">
          <div className="space-y-6">
            <div className="inline-flex items-center space-x-2 bg-white rounded-full px-3 py-1 border border-blue-100 shadow-sm">
              <Sparkles size={14} className="text-blue-600" />
              <span className="text-xs font-medium text-blue-800">
                {t('home.aiPowered')}
              </span>
            </div>
            <h1 className="text-3xl sm:text-4xl lg:text-5xl font-extrabold leading-tight text-gray-900">
              {t('home.heroTitlePrefix')} <br />
              <span className="text-transparent bg-clip-text bg-gradient-to-r from-blue-600 to-purple-600">
                {t('home.heroTitleHighlight')}
              </span>
            </h1>
            <p className="text-lg text-gray-600 max-w-lg">
              {t('home.heroDescription')}
            </p>

            <div className="bg-white p-2 rounded-xl shadow-xl border border-gray-100 max-w-xl">
              <div className="flex flex-col gap-2 sm:relative sm:block">
                <input
                  type="text"
                  value={searchInput}
                  onChange={e => setSearchInput(e.target.value)}
                  onKeyDown={e => e.key === 'Enter' && handleSearch()}
                  placeholder={t('home.searchPlaceholder')}
                  className="w-full rounded-lg border-transparent bg-gray-50 px-4 py-3 text-gray-900 transition-all focus:border-blue-500 focus:bg-white focus:ring-2 focus:ring-blue-200 sm:pr-28"
                />
                <Button
                  className="w-full sm:absolute sm:bottom-1 sm:right-1 sm:top-1 sm:w-auto"
                  size="sm"
                  onClick={() => handleSearch()}
                >
                  <Search size={16} className="mr-1" /> {t('common.search')}
                </Button>
              </div>
              <div className="mt-3 px-2 flex flex-wrap gap-2 text-xs items-center">
                {history.length > 0 ? (
                  <>
                    <span className="text-gray-400 font-medium">{t('home.recentSearches')}</span>
                    {history.map(item => (
                      <span
                        key={item.id}
                        onClick={() => handleSearch(item.keyword)}
                        className="flex items-center gap-1 px-2 py-1 bg-gray-100 text-gray-600 rounded-md cursor-pointer hover:bg-blue-50 hover:text-blue-600 transition-colors group"
                      >
                        {item.keyword}
                        <button
                          onClick={(e) => handleDeleteHistory(item.id, e)}
                          className="opacity-0 group-hover:opacity-100 ml-0.5 text-gray-400 hover:text-red-500 transition-opacity"
                        >
                          ×
                        </button>
                      </span>
                    ))}
                  </>
                ) : (
                  <>
                    <span className="text-gray-400 font-medium">{t('home.suggestions')}</span>
                    {['Python cơ bản', 'Machine learning', 'Kinh tế vi mô'].map(kw => (
                      <span
                        key={kw}
                        onClick={() => handleSearch(kw)}
                        className="px-2 py-1 bg-gray-100 text-gray-600 rounded-md cursor-pointer hover:bg-blue-50 hover:text-blue-600 transition-colors"
                      >
                        {kw}
                      </span>
                    ))}
                  </>
                )}
              </div>
            </div>

            <div className="flex items-center space-x-6 text-sm font-medium pt-2">
              <Link
                to={`${publicPrefix}/search?sort=newest`}
                className="text-blue-600 hover:text-blue-800 flex items-center group"
              >
                {t('home.viewNewest')}{' '}
                <ArrowRight
                  size={16}
                  className="ml-1 group-hover:translate-x-1 transition-transform"
                />
              </Link>
              <Link
                to={`${publicPrefix}/categories`}
                className="text-gray-600 hover:text-gray-900 flex items-center group"
              >
                {t('home.exploreTopics')}{' '}
                <ArrowRight
                  size={16}
                  className="ml-1 group-hover:translate-x-1 transition-transform"
                />
              </Link>
            </div>
          </div>

          <div className="hidden lg:block relative">
            <div className="relative rounded-2xl overflow-hidden shadow-2xl border-4 border-white">
              <img
                src="https://images.unsplash.com/photo-1521587760476-6c12a4b040da?ixlib=rb-4.0.3&auto=format&fit=crop&w=1000&q=80"
                alt="Library"
                className="w-full h-auto object-cover transform scale-105"
              />

              {/* Floating Card 1 */}
              <div className="absolute bottom-8 left-8 bg-white/95 backdrop-blur-md rounded-xl p-4 shadow-lg flex items-center space-x-4 border border-white/50 animate-bounce-slow">
                <div className="p-3 bg-green-100 rounded-full text-green-600">
                  <CheckCircle size={24} />
                </div>
                <div>
                  <p className="text-2xl font-bold text-gray-900">
                    {formatCompactNumber(stats.totalPublications)}
                  </p>
                  <p className="text-sm text-gray-500 font-medium">
                    {t('home.availableMaterials')}
                  </p>
                </div>
              </div>

              {/* Floating Card 2 */}
              <div
                className="absolute top-8 right-8 bg-white/95 backdrop-blur-md rounded-xl p-4 shadow-lg flex items-center space-x-4 border border-white/50 animate-bounce-slow"
                style={{ animationDelay: '1s' }}
              >
                <div className="p-3 bg-blue-100 rounded-full text-blue-600">
                  <Users size={24} />
                </div>
                <div>
                  <p className="text-2xl font-bold text-gray-900">
                    {formatCompactNumber(stats.activeUsers)}
                  </p>
                  <p className="text-sm text-gray-500 font-medium">
                    {t('home.activeStudents')}
                  </p>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
};

const SectionHeader = ({
  title,
  subtitle,
  linkText,
  linkUrl,
}: {
  title: string;
  subtitle?: string;
  linkText?: string;
  linkUrl?: string;
}) => (
  <div className="flex justify-between items-end mb-8">
    <div>
      {subtitle && (
        <div className="flex items-center space-x-2 mb-2">
          <div className="w-1 h-4 bg-blue-600 rounded-full"></div>
          <span className="text-xs font-bold text-blue-600 uppercase tracking-wide">
            {subtitle}
          </span>
        </div>
      )}
      <h2 className="text-2xl lg:text-3xl font-bold text-gray-900">{title}</h2>
    </div>
    {linkText && linkUrl && (
      <Link
        to={linkUrl}
        className="text-sm font-medium text-blue-600 hover:text-blue-800 flex items-center group"
      >
        {linkText}{' '}
        <ArrowRight
          size={16}
          className="ml-1 group-hover:translate-x-1 transition-transform"
        />
      </Link>
    )}
  </div>
);

const BookCard = ({
  id,
  title,
  author,
  rating,
  reviews,
  image,
  available,
  loans,
  tag,
  color = 'blue',
}: any) => {
  const { t } = useTranslation();
  const publicPrefix = usePublicPrefix();

  return (
  <div className="bg-white rounded-xl border border-gray-100 shadow-sm hover:shadow-xl transition-all duration-300 group flex flex-col h-full">
    <Link to={`${publicPrefix}/book/${id || 1}`} className="relative aspect-[3/4] w-full overflow-hidden rounded-t-xl bg-gray-100 block">
      <img
        src={image}
        alt={title}
        className="w-full h-full object-cover group-hover:scale-105 transition-transform duration-500"
      />
      <div className="absolute top-3 left-3 flex flex-col gap-2">
        {tag && (
          <span
            className={`bg-${color}-600 text-white text-[10px] font-bold px-2 py-1 rounded shadow-sm`}
          >
            {tag}
          </span>
        )}
      </div>
      <div className="absolute bottom-3 left-3">
        <span className="bg-white/90 backdrop-blur text-gray-800 text-[10px] font-bold px-2 py-1 rounded shadow-sm flex items-center">
          <span
            className={`w-1.5 h-1.5 rounded-full bg-${
              available > 0 ? 'green' : 'red'
            }-500 mr-1.5`}
          ></span>
          {available} {t('common.availableCopies')}
        </span>
      </div>
    </Link>
    <div className="p-4 flex flex-col flex-grow">
      <h3 className="font-bold text-gray-900 line-clamp-2 text-base mb-1 leading-snug group-hover:text-blue-600 transition-colors">
        <Link to={`${publicPrefix}/book/${id || 1}`}>{title}</Link>
      </h3>
      <p className="text-xs text-gray-500 mb-3 truncate">{author}</p>

      <div className="mt-auto pt-3 border-t border-gray-50">
        <div className="flex items-center justify-between gap-2 mb-4">
          <div className="flex items-center">
            <StarRating rating={rating} size={14} />
            <span className="text-[10px] text-gray-400 ml-1">({reviews})</span>
          </div>
          <span className="inline-flex items-center text-[10px] font-medium text-gray-500">
            <Users size={11} className="mr-1" /> {loans ?? 0} {t('home.borrowCount')}
          </span>
        </div>
      </div>
        <Link to={`${publicPrefix}/book/${id || 1}`}>
          <Button
            size="sm"
            variant="ghost"
            className="text-xs py-1 px-2 h-8 text-blue-600 bg-blue-50 hover:bg-blue-100"
          >
            {t('common.viewDetails')}
          </Button>
        </Link>
    </div>
  </div>
  );
};

const CATEGORY_ACCENTS = [
  { icon: <Sparkles size={20} />, color: 'bg-blue-500' },
  { icon: <Code size={20} />, color: 'bg-indigo-500' },
  { icon: <Database size={20} />, color: 'bg-red-500' },
  { icon: <BarChart size={20} />, color: 'bg-green-500' },
  { icon: <Globe size={20} />, color: 'bg-pink-500' },
  { icon: <Book size={20} />, color: 'bg-yellow-500' },
  { icon: <Scale size={20} />, color: 'bg-gray-600' },
  { icon: <CheckCircle size={20} />, color: 'bg-purple-500' },
];

const getCategoryName = (category: Category) =>
  category.name || category.categoryName || 'Danh mục chưa đặt tên';

const formatCompactNumber = (value?: number) => {
  const numericValue = value ?? 0;
  return numericValue.toLocaleString('vi-VN');
};

const CategoryItem = ({ category, accent }: { category: Category; accent: typeof CATEGORY_ACCENTS[number] }) => {
  const { t } = useTranslation();
  const publicPrefix = usePublicPrefix();

  return (
  <Link
    to={`${publicPrefix}/search?categoryId=${category.id}`}
    className="block bg-white border border-gray-100 rounded-xl p-5 hover:border-blue-200 hover:shadow-lg transition-all cursor-pointer group"
  >
    <div
      className={`w-10 h-10 rounded-lg flex items-center justify-center mb-4 ${accent.color} text-white shadow-md group-hover:scale-110 transition-transform`}
    >
      {accent.icon}
    </div>
    <h3 className="text-base font-bold text-gray-900 group-hover:text-blue-600 mb-1">
      {category.name || category.categoryName || t('categories.unnamed')}
    </h3>
    <p className="text-xs text-gray-500 mb-3 line-clamp-2 min-h-[32px]">
      {category.bio || t('categories.defaultDescription')}
    </p>
    <div className="flex items-center justify-between text-xs font-medium text-blue-600">
      <span>{formatCompactNumber(category.publicationCount)} {t('common.books')}</span>
      <ArrowRight size={12} className="ml-1 opacity-0 group-hover:opacity-100 transition-opacity" />
    </div>
  </Link>
  );
};

const TrendingItem = ({
  id,
  rank,
  title,
  author,
  loans,
  available,
  rating,
  category,
  image,
}: any) => {
  const { t } = useTranslation();
  const publicPrefix = usePublicPrefix();

  return (
  <div className="flex items-start bg-white p-4 rounded-xl border border-gray-100 hover:shadow-md transition-shadow relative overflow-hidden group">
    <div className="absolute top-0 left-0 w-1 h-full bg-gradient-to-b from-blue-400 to-purple-500"></div>
    <Link to={`${publicPrefix}/book/${id || 1}`} className="mr-4 relative flex-shrink-0">
      <img
        src={image || BOOK_COVER_PLACEHOLDER}
        alt={title}
        className="w-20 h-28 object-cover rounded shadow-sm"
      />
      <div className="absolute -top-2 -left-2 w-8 h-8 bg-gray-900 text-white flex items-center justify-center rounded-full font-bold text-sm border-2 border-white shadow">
        #{rank}
      </div>
    </Link>
    <div className="flex-grow min-w-0">
      <div className="flex items-center space-x-2 mb-1">
        <Badge variant="secondary" className="text-[10px] py-0">
          {category}
        </Badge>
      </div>
      <h3 className="font-bold text-gray-900 text-sm mb-1 truncate pr-2 group-hover:text-blue-600">
        <Link to={`${publicPrefix}/book/${id || 1}`}>{title}</Link>
      </h3>
      <p className="text-xs text-gray-500 mb-2">{author}</p>
      <div className="flex items-center space-x-3 text-xs text-gray-500 mb-2">
        <span className="flex items-center">
          <Users size={12} className="mr-1" /> {loans} {t('home.borrowCount')}
        </span>
        <span className="flex items-center">
          <CheckCircle size={12} className="mr-1 text-green-500" /> {available}{' '}
          {t('common.copies')}
        </span>
      </div>
      <StarRating rating={rating} size={12} />
    </div>
    <div className="absolute bottom-4 right-4 opacity-0 group-hover:opacity-100 transition-opacity">
      <Link
        to={`${publicPrefix}/book/${id || 1}`}
        className="text-xs font-bold text-blue-600 hover:underline"
      >
        {t('common.viewDetails')}
      </Link>
    </div>
  </div>
  );
};

const FeatureBox = ({ icon, title, desc }: any) => {
  const publicPrefix = usePublicPrefix();
  return (
  <div className="bg-white p-6 rounded-2xl border border-gray-100 shadow-sm hover:shadow-xl transition-shadow text-center group">
    <div className="w-16 h-16 bg-blue-50 rounded-full flex items-center justify-center mx-auto mb-6 group-hover:bg-blue-600 transition-colors">
      <div className="text-blue-600 group-hover:text-white transition-colors">
        {icon}
      </div>
    </div>
    <h3 className="text-lg font-bold text-gray-900 mb-3">{title}</h3>
    <p className="text-sm text-gray-500 leading-relaxed">{desc}</p>
    <Link
      to={`${publicPrefix}/guide`}
      className="inline-flex items-center text-sm font-medium text-blue-600 mt-4 hover:underline"
    >
      <FeatureLearnMore />
    </Link>
  </div>
  );
};

const FeatureLearnMore = () => {
  const { t } = useTranslation();
  return <>{t('common.learnMore')} <ChevronRight size={14} /></>;
};

const statColors: Record<string, { bg: string; text: string }> = {
  blue: { bg: 'bg-blue-50', text: 'text-blue-600' },
  purple: { bg: 'bg-purple-50', text: 'text-purple-600' },
  green: { bg: 'bg-green-50', text: 'text-green-600' },
  pink: { bg: 'bg-pink-50', text: 'text-pink-600' },
};

const StatBox = ({ number, label, icon: Icon, color }: any) => {
  const colorClass = statColors[color] || statColors.blue;
  return (
  <div className={`flex flex-col items-center justify-center p-8 ${colorClass.bg} rounded-2xl`}>
    <Icon size={32} className={`${colorClass.text} mb-4`} />
    <span className="text-4xl font-extrabold text-gray-900 mb-2">{number}</span>
    <span className="text-sm font-medium text-gray-500 uppercase tracking-wider">
      {label}
    </span>
  </div>
  );
};

const TestimonialCard = ({ quote, name, role, avatar, star = 5 }: any) => (
  <div className="bg-white p-6 rounded-2xl shadow-sm border border-gray-100 hover:shadow-lg hover:-translate-y-0.5 transition-all duration-200">
    <div className="flex items-center justify-between mb-4">
      <div className="flex gap-1 text-yellow-400">
      {Array.from({ length: 5 }).map((_, index) => (
        <Star
          key={index}
          size={16}
          fill={index < star ? 'currentColor' : 'none'}
          className={index < star ? 'text-yellow-400' : 'text-gray-300'}
        />
      ))}
      </div>
      <span className="text-xs font-semibold text-gray-500">{star}/5</span>
    </div>
    <p className="text-gray-700 italic mb-6 text-sm leading-relaxed line-clamp-4">
      "{quote}"
    </p>
    <div className="flex items-center">
      <img
        src={avatar}
        alt={name}
        className="w-10 h-10 rounded-full object-cover mr-3"
      />
      <div>
        <h4 className="font-bold text-gray-900 text-sm">{name}</h4>
        <p className="text-xs text-gray-500">{role}</p>
      </div>
    </div>
  </div>
);

const ReviewStarsInput = ({
  value,
  onChange,
}: {
  value: number;
  onChange: (value: number) => void;
}) => (
  <div className="flex items-center gap-1">
    {Array.from({ length: 5 }).map((_, index) => {
      const starValue = index + 1;
      return (
        <button
          key={starValue}
          type="button"
          onClick={() => onChange(starValue)}
          className="p-1 rounded-md hover:bg-yellow-50 transition-colors"
          aria-label={`${starValue} sao`}
        >
          <Star
            size={28}
            fill={starValue <= value ? 'currentColor' : 'none'}
            className={starValue <= value ? 'text-yellow-400' : 'text-gray-300'}
          />
        </button>
      );
    })}
  </div>
);

const fallbackStats: PublicLibraryStats = {
  totalPublications: 0,
  activeUsers: 0,
  totalBorrows: 0,
  totalCategories: 0,
  averageRating: 0,
  totalRatings: 0,
  satisfactionPercent: 0,
};

const HomePage = () => {
  const { userType, sessionVersion } = useAuth();
  const location = useLocation();
  const navigate = useNavigate();
  const { language, t } = useTranslation();
  const publicPrefix = location.pathname.startsWith('/librarianpage/public') ? '/librarianpage/public' : '/publicpage';
  const isLibrarianPublic = location.pathname.startsWith('/librarianpage/public');
  const [newestPublications, setNewestPublications] = useState<NewestPublication[]>([]);
  const [loadingNewest, setLoadingNewest] = useState(true);

  const [mostBorrowedPublications, setMostBorrowedPublications] = useState<MostBorrowedPublication[]>([]);
  const [loadingMostBorrowed, setLoadingMostBorrowed] = useState(true);

  const [recommendations, setRecommendations] = useState<RecommendedPublication[]>([]);
  const [loadingRecs, setLoadingRecs] = useState(false);
  const [refreshingRecs, setRefreshingRecs] = useState(false);
  const [recsMessage, setRecsMessage] = useState<{ error: boolean; text: string } | null>(null);
  const recommendationRequest = useRef(0);
  const [categories, setCategories] = useState<Category[]>([]);
  const [loadingCategories, setLoadingCategories] = useState(true);
  const [stats, setStats] = useState<PublicLibraryStats>(fallbackStats);
  const [testimonials, setTestimonials] = useState<SystemReview[]>([]);
  const [myReview, setMyReview] = useState<SystemReview | null>(null);
  const [reviewModalOpen, setReviewModalOpen] = useState(false);
  const [reviewRating, setReviewRating] = useState(5);
  const [reviewComment, setReviewComment] = useState('');
  const [reviewSubmitting, setReviewSubmitting] = useState(false);
  const [reviewMessage, setReviewMessage] = useState<string | null>(null);

  useEffect(() => {
    const request = ++recommendationRequest.current;
    setRecommendations([]);
    setRecsMessage(null);
    setRefreshingRecs(false);
    if (userType !== 'student') { setLoadingRecs(false); return; }
    setLoadingRecs(true);
    recommendationService.getRecommendations(10)
      .then(res => { if (request === recommendationRequest.current && res.code === 200) setRecommendations(res.data ?? []); })
      .catch(() => {})
      .finally(() => { if (request === recommendationRequest.current) setLoadingRecs(false); });
    return () => { ++recommendationRequest.current; };
  }, [userType, sessionVersion]);

  const refreshPersonalization = async () => {
    if (refreshingRecs || loadingRecs) return;
    const request = ++recommendationRequest.current;
    setRefreshingRecs(true);
    setRecsMessage(null);
    try {
      const response = await recommendationService.refreshRecommendations(10);
      if (request !== recommendationRequest.current) return;
      if (response.code !== 200) throw new Error('Refresh failed');
      setRecommendations(response.data ?? []);
      setRecsMessage({ error: false, text: language === 'vi' ? 'Đã cập nhật gợi ý cá nhân hóa.' : 'Personalized recommendations updated.' });
    } catch {
      if (request === recommendationRequest.current) setRecsMessage({ error: true, text: language === 'vi'
        ? 'Chưa cập nhật được gợi ý. Danh sách hiện tại được giữ lại; bạn hãy thử lại sau.'
        : 'Could not refresh recommendations. Your current list is kept; please try again later.' });
    } finally { if (request === recommendationRequest.current) setRefreshingRecs(false); }
  };

  useEffect(() => {
    const fetchNewest = async () => {
      try {
        const response = await publicationsService.getNewestPublications(4);
        if (response && response.code === 200) {
          setNewestPublications(response.data || []);
        }
      } catch (error) {
        console.error("Failed to fetch newest publications", error);
      } finally {
        setLoadingNewest(false);
      }
    };

    const fetchMostBorrowed = async () => {
      try {
        setLoadingMostBorrowed(true);
        const response = await publicationsService.getMostBorrowedPublications(4);
        if (response && response.code === 200) {
          setMostBorrowedPublications(response.data || []);
        }
      } catch (error) {
        console.error("Failed to fetch most borrowed publications", error);
      } finally {
        setLoadingMostBorrowed(false);
      }
    };

    fetchNewest();
    fetchMostBorrowed();

    categoriesService.getAllCategories()
      .then(res => {
        if (res.code === 200) setCategories(res.data ?? []);
      })
      .catch(() => setCategories([]))
      .finally(() => setLoadingCategories(false));

    publicationsService.getPublicStats()
      .then(res => {
        if (res.code === 200 && res.data) setStats(res.data);
      })
      .catch(() => setStats(fallbackStats));

    systemReviewsService.getTopReviews(3)
      .then(res => {
        if (res.code === 200) setTestimonials(res.data ?? []);
      })
      .catch(() => setTestimonials([]));

    systemReviewsService.getSummary()
      .then(res => {
        if (res.code === 200 && res.data) {
          setStats(prev => ({
            ...prev,
            totalRatings: res.data.totalReviews,
            averageRating: res.data.averageRating,
            satisfactionPercent: res.data.satisfactionPercent,
          }));
        }
      })
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    if (userType !== 'student') {
      setMyReview(null);
      return;
    }
    systemReviewsService.getMyReview()
      .then(res => {
        const review = res.data ?? null;
        setMyReview(review);
        if (review) {
          setReviewRating(review.rating);
          setReviewComment(review.comment);
        }
      })
      .catch(() => setMyReview(null));
  }, [userType]);

  const openReviewModal = () => {
    if (userType !== 'student') {
      navigate('/publicpage/login', { state: { from: location.pathname } });
      return;
    }
    setReviewMessage(null);
    setReviewRating(myReview?.rating ?? 5);
    setReviewComment(myReview?.comment ?? '');
    setReviewModalOpen(true);
  };

  const submitSystemReview = async () => {
    const comment = reviewComment.trim();
    if (!comment) {
      setReviewMessage(t('home.reviewRequired'));
      return;
    }
    setReviewSubmitting(true);
    setReviewMessage(null);
    try {
      const response = await systemReviewsService.upsertMyReview({
        rating: reviewRating,
        comment,
      });
      if (response.code === 200 && response.data) {
        setMyReview(response.data);
        setReviewModalOpen(false);
        const topResponse = await systemReviewsService.getTopReviews(3);
        if (topResponse.code === 200) setTestimonials(topResponse.data ?? []);
        const statsResponse = await systemReviewsService.getSummary();
        if (statsResponse.code === 200 && statsResponse.data) {
          setStats(prev => ({
            ...prev,
            totalRatings: statsResponse.data.totalReviews,
            averageRating: statsResponse.data.averageRating,
            satisfactionPercent: statsResponse.data.satisfactionPercent,
          }));
        }
      }
    } catch (error: any) {
      setReviewMessage(error?.message || t('home.reviewSaveFailed'));
    } finally {
      setReviewSubmitting(false);
    }
  };

  const featuredCategories = [...categories]
    .sort((a, b) => (b.publicationCount ?? 0) - (a.publicationCount ?? 0) || getCategoryName(a).localeCompare(getCategoryName(b), 'vi'))
    .slice(0, 8);

  const displayTestimonials = testimonials.length > 0
    ? testimonials.map((item) => ({
        quote: item.comment?.trim() || t('home.defaultReviewQuote').replace('{rating}', String(item.rating)),
        name: item.fullName,
        role: formatReviewerAcademicLine({
          studentId: item.studentId,
          faculty: item.faculty,
          role: item.role,
          fallback: item.role,
          language,
        }),
        avatar: item.profilePictureUrl || `https://ui-avatars.com/api/?name=${encodeURIComponent(item.fullName)}&background=2563eb&color=fff`,
        star: item.rating,
      }))
    : [];

  return (
    <div className="bg-white">
      <HeroSection stats={stats} />

      {/* Recommended Section - Gợi ý cho bạn */}
      {!isLibrarianPublic && (
      <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-16 bg-white">
        <div className="flex flex-wrap justify-between items-end gap-4 mb-8">
          <div>
            <div className="flex items-center space-x-2 mb-2">
              <div className="w-1 h-4 bg-purple-600 rounded-full"></div>
              <span className="text-xs font-bold text-purple-600 uppercase tracking-wide">
                {t('home.forYouEyebrow')}
              </span>
            </div>
            <h2 className="text-2xl lg:text-3xl font-bold text-gray-900">
              {t('home.forYouTitle')}
            </h2>
            <p className="text-sm text-gray-500 mt-1">
              {t('home.forYouSubtitle')}
            </p>
          </div>
          <div className="flex flex-wrap items-center gap-4">
          {userType === 'student' && (
            <button type="button" onClick={refreshPersonalization} disabled={loadingRecs || refreshingRecs}
              className="inline-flex items-center gap-2 rounded-lg border border-purple-200 bg-purple-50 px-3 py-2 text-sm font-semibold text-purple-700 hover:bg-purple-100 disabled:opacity-50 disabled:cursor-wait dark:border-purple-700 dark:bg-purple-950 dark:text-purple-200"
              aria-busy={refreshingRecs}>
              <RefreshCw size={16} className={refreshingRecs ? 'animate-spin' : ''} />
              {refreshingRecs ? (language === 'vi' ? 'Đang cập nhật…' : 'Updating…')
                : (language === 'vi' ? 'Cập nhật cá nhân hóa' : 'Refresh personalization')}
            </button>
          )}
          <Link
            to={`${publicPrefix}/search`}
            className="text-sm font-medium text-blue-600 hover:text-blue-800 flex items-center group"
          >
            {t('common.viewAll')}{' '}
            <ArrowRight
              size={16}
              className="ml-1 group-hover:translate-x-1 transition-transform"
            />
          </Link>
          </div>
        </div>
        {recsMessage && <p role={recsMessage.error ? 'alert' : 'status'} className={`mb-4 text-sm ${recsMessage.error ? 'text-amber-700 dark:text-amber-300' : 'text-emerald-700 dark:text-emerald-300'}`}>{recsMessage.text}</p>}
        {!userType ? (
          <p className="text-sm text-gray-400 italic">{t('home.loginForRecommendations')}</p>
        ) : loadingRecs ? (
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
            {Array.from({ length: 5 }).map((_, i) => (
              <div key={i} className="bg-gray-100 rounded-xl aspect-[3/4] animate-pulse" />
            ))}
          </div>
        ) : recommendations.length === 0 ? (
          <p className="text-sm text-gray-400 italic">{t('home.emptyRecommendations')}</p>
        ) : (
          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 gap-6">
            {recommendations.map((pub) => (
              <BookCard
                key={pub.publicationId}
                id={pub.publicationId}
                title={pub.title}
                author={pub.authorNames.join(', ')}
                rating={pub.ratingAverage}
                reviews={pub.ratingCount}
                available={pub.availableItems}
                loans={pub.borrowCount ?? 0}
                image={pub.coverImageUrl ?? BOOK_COVER_PLACEHOLDER}
                color="purple"
              />
            ))}
          </div>
        )}
      </section>
      )}

      {/* Categories - Khám phá theo chủ đề */}
      <section className="bg-gray-50 py-16">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="text-center mb-12">
            <span className="text-xs font-bold text-gray-500 uppercase tracking-wide">
              {t('home.categoriesEyebrow')}
            </span>
            <h2 className="text-3xl font-bold text-gray-900 mt-2">
              {t('home.categoriesTitle')}
            </h2>
            <p className="text-gray-500 mt-2">
              {t('home.categoriesSubtitle')}
            </p>
          </div>

          {loadingCategories ? (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
              {Array.from({ length: 8 }).map((_, index) => (
                <div key={index} className="h-40 rounded-xl bg-white border border-gray-100 animate-pulse" />
              ))}
            </div>
          ) : featuredCategories.length === 0 ? (
            <div className="rounded-xl bg-white border border-gray-100 p-8 text-center text-gray-500">
              {t('home.noCategories')}
            </div>
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
              {featuredCategories.map((category, index) => (
                <CategoryItem
                  key={category.id}
                  category={category}
                  accent={CATEGORY_ACCENTS[index % CATEGORY_ACCENTS.length]}
                />
              ))}
            </div>
          )}
          <div className="text-center mt-10">
            <Link to={`${publicPrefix}/categories`}>
              <Button variant="outline" className="px-8">
                {t('home.allTopics')}
              </Button>
            </Link>
          </div>
        </div>
      </section>

      {/* New Books & Trending - Sách mới & Được mượn nhiều */}
      <section className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-16">
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-12">
          {/* Sách Mới (Left - Carousel/Grid style) */}
          <div className="lg:col-span-2">
            <SectionHeader
              title={t('home.newBooks')}
              subtitle={t('home.newUpdated')}
              linkText={t('common.viewAll')}
              linkUrl={`${publicPrefix}/search?sort=newest`}
            />
            <div className="grid grid-cols-2 sm:grid-cols-4 gap-4">
              {loadingNewest ? (
                <div className="col-span-full py-10 text-center">{t('home.loadingNewBooks')}</div>
              ) : newestPublications.length > 0 ? (
                newestPublications.slice(0, 4).map(pub => (
                  <BookCard
                    key={pub.publicationId}
                    id={pub.publicationId}
                    title={pub.title}
                    author={pub.authorNames.join(', ')}
                    rating={pub.ratingAverage}
                    reviews={pub.ratingCount}
                    available={pub.availableItems}
                    loans={pub.borrowCount ?? 0}
                    image={pub.coverImageUrl || BOOK_COVER_PLACEHOLDER}
                    tag={t('home.newTag')}
                    color="green"
                  />
                ))
              ) : (
                <div className="col-span-full py-10 text-center text-gray-500">{t('home.noNewBooks')}</div>
              )}
            </div>
          </div>

          {/* Được mượn nhiều (Right - List style) */}
          <div>
            <SectionHeader
              title={t('home.popularBooks')}
              subtitle={t('home.trending')}
              linkText={t('common.viewAll')}
              linkUrl={`${publicPrefix}/search?sort=most_borrowed`}
            />
            <div className="space-y-4">
              {loadingMostBorrowed ? (
                <div className="py-10 text-center">{t('home.loadingPopularBooks')}</div>
              ) : mostBorrowedPublications.length > 0 ? (
                mostBorrowedPublications.slice(0, 4).map((pub, index) => (
                  <TrendingItem
                    key={pub.publicationId}
                    id={pub.publicationId}
                    rank={index + 1}
                    title={pub.title}
                    author={pub.authorNames.join(', ')}
                    loans={pub.borrowCount || 0}
                    available={pub.availableItems}
                    rating={pub.ratingAverage}
                    category={t('common.book')}
                    image={pub.coverImageUrl}
                  />
                ))
              ) : (
                <div className="py-10 text-center text-gray-500">{t('home.noPopularBooks')}</div>
              )}
            </div>
          </div>
        </div>
      </section>

      {/* AI Features - Công nghệ AI */}
      {!isLibrarianPublic && (
      <section className="bg-gray-50 py-20 relative overflow-hidden">
        {/* Decorative background shapes */}
        <div className="absolute -top-24 -left-24 hidden w-96 h-96 bg-blue-200/30 rounded-full blur-3xl sm:block"></div>
        <div className="absolute -bottom-24 -right-24 hidden w-96 h-96 bg-purple-200/30 rounded-full blur-3xl sm:block"></div>

        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 relative z-10">
          <div className="text-center max-w-3xl mx-auto mb-16">
            <div className="inline-flex items-center space-x-2 bg-white rounded-full px-3 py-1 border border-purple-100 shadow-sm mb-4">
              <Sparkles size={14} className="text-purple-600" />
              <span className="text-xs font-bold text-purple-700 uppercase">
                {t('home.aiTechnology')}
              </span>
            </div>
            <h2 className="text-3xl md:text-4xl font-bold text-gray-900 mb-4">
              {t('home.aiHelpTitle')}
            </h2>
            <p className="text-lg text-gray-600">
              {t('home.aiHelpDesc')}
            </p>
          </div>

          <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
            <FeatureBox
              icon={<Search size={32} />}
              title={t('home.contextTitle')}
              desc={t('home.contextDesc')}
            />
            <FeatureBox
              icon={<BarChart size={32} />}
              title={t('home.behaviorTitle')}
              desc={t('home.behaviorDesc')}
            />
            <FeatureBox
              icon={<Sparkles size={32} />}
              title={t('home.discoveryTitle')}
              desc={t('home.discoveryDesc')}
            />
          </div>

          {!userType && (
            <div className="mt-10 md:mt-16 bg-gradient-to-r from-blue-600 to-indigo-700 rounded-2xl md:rounded-3xl p-5 sm:p-8 md:p-12 text-white flex flex-col md:flex-row items-start md:items-center justify-between gap-6 shadow-2xl relative overflow-hidden">
              <div className="absolute inset-0 bg-[url('https://www.transparenttextures.com/patterns/cubes.png')] opacity-10"></div>
              <div className="relative z-10 max-w-2xl">
                <h3 className="text-2xl font-bold mb-4">
                  {t('home.tryAiTitle')}
                </h3>
                <p className="text-blue-100 mb-6">
                  {t('home.tryAiDesc')}
                </p>
                <ul className="space-y-2 mb-0">
                  <li className="flex items-center">
                    <CheckCircle size={16} className="text-green-400 mr-2" /> {t('home.freeForStudents')}
                  </li>
                  <li className="flex items-center">
                    <CheckCircle size={16} className="text-green-400 mr-2" />{' '}
                    {t('home.unlimitedSearch')}
                  </li>
                  <li className="flex items-center">
                    <CheckCircle size={16} className="text-green-400 mr-2" />
                    {t('home.support247')}
                  </li>
                </ul>
              </div>
              <div className="relative z-10 mt-8 md:mt-0 flex-shrink-0">
                <Link to={`${publicPrefix}/register`}>
                  <Button
                    size="lg"
                    variant="outline"
                    className="
        bg-white
        text-blue-800
        border border-white/80
        text-xs font-semibold
        px-5 py-2
        shadow-sm
        transition-all duration-200
        hover:bg-emerald-50
        hover:text-emerald-700
        hover:shadow-md
        hover:-translate-y-[1px]
      "
                  >
                    {t('home.freeRegister')} <ArrowRight size={18} className="ml-2" />
                  </Button>
                </Link>
              </div>
            </div>
          )}
        </div>
      </section>
      )}

      {/* Stats - Con số ấn tượng */}
      <section className="bg-white py-20">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 text-center">
          <h2 className="text-3xl font-bold text-gray-900 mb-2">
            {t('home.statsTitle')}
          </h2>
          <p className="text-gray-500 mb-12">
            {t('home.statsSubtitle')}
          </p>

          <div className="grid grid-cols-2 md:grid-cols-4 gap-6">
            <StatBox
              number={formatCompactNumber(stats.totalPublications)}
              label={t('home.materials')}
              icon={Book}
              color="blue"
            />
            <StatBox
              number={formatCompactNumber(stats.activeUsers)}
              label={t('home.users')}
              icon={Users}
              color="purple"
            />
            <StatBox
              number={formatCompactNumber(stats.totalBorrows)}
              label={t('home.borrows')}
              icon={ChevronRight}
              color="green"
            />
            <StatBox
              number={stats.totalRatings > 0 ? `${stats.satisfactionPercent}%` : '0'}
              label={t('home.satisfaction')}
              icon={CheckCircle}
              color="pink"
            />
          </div>
        </div>
      </section>

      {/* Testimonials - Sinh viên nói gì */}
      <section className="bg-gray-50 py-20 border-t border-gray-100">
        <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
          <div className="flex flex-col lg:flex-row lg:items-end lg:justify-between gap-6 mb-12">
            <div className="text-center lg:text-left">
              <div className="inline-flex items-center space-x-2 mb-2">
                <Star size={14} className="text-yellow-500 fill-yellow-500" />
                <span className="text-xs font-bold text-gray-500 uppercase">
                  {t('home.testimonialsEyebrow')}
                </span>
              </div>
              <h2 className="text-3xl font-bold text-gray-900">
                {t('home.testimonialsTitle')}
              </h2>
              <p className="text-gray-500 mt-2">
                {t('home.testimonialsSubtitle')}
              </p>
            </div>

            <div className="flex flex-col sm:flex-row items-center justify-center lg:justify-end gap-3">
              <div className="flex items-center gap-3 rounded-2xl bg-white border border-gray-100 px-4 py-3 shadow-sm">
                <div className="w-10 h-10 rounded-xl bg-yellow-50 text-yellow-600 flex items-center justify-center">
                  <Star size={20} fill="currentColor" />
                </div>
                <div className="text-left">
                  <p className="text-sm font-bold text-gray-900">
                    {stats.averageRating?.toFixed?.(1) ?? '0.0'}/5
                  </p>
                  <p className="text-xs text-gray-500">
                    {formatCompactNumber(stats.totalRatings)} {t('home.reviewCountLabel')} · {stats.satisfactionPercent}% {t('home.satisfiedLabel')}
                  </p>
                </div>
              </div>
              {!isLibrarianPublic && (
                <Button onClick={openReviewModal} className="gap-2">
                  {myReview ? <PenLine size={16} /> : <MessageSquare size={16} />}
                  {myReview ? t('home.editSystemReview') : t('home.reviewSystem')}
                </Button>
              )}
              <Link to={`${publicPrefix}/reviews`}>
                <Button variant="outline" className="gap-2">
                  {t('home.viewAllReviews')} <ArrowRight size={16} />
                </Button>
              </Link>
            </div>
          </div>

          {displayTestimonials.length > 0 ? (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-8">
              {displayTestimonials.map((testimonial, index) => (
                <TestimonialCard key={`${testimonial.name}-${index}`} {...testimonial} />
              ))}
            </div>
          ) : (
            <div className="rounded-2xl bg-white border border-gray-100 p-10 text-center shadow-sm">
              <div className="w-12 h-12 rounded-2xl bg-gray-50 text-gray-400 flex items-center justify-center mx-auto mb-4">
                <MessageSquare size={24} />
              </div>
              <p className="font-semibold text-gray-900">{t('home.noSystemReviews')}</p>
              <p className="text-sm text-gray-500 mt-2">
                {t('home.noSystemReviewsDesc')}
              </p>
            </div>
          )}
        </div>
      </section>

      {!userType && (
        <section className="bg-blue-700 py-16 text-center text-white relative overflow-hidden">
          <div className="absolute top-0 left-0 w-full h-full bg-gradient-to-r from-blue-700 to-indigo-800 opacity-90"></div>
          <div className="relative z-10 max-w-4xl mx-auto px-4">
            <h2 className="text-3xl md:text-4xl font-bold mb-6">
              {t('home.readyTitle')}
            </h2>
            <p className="text-blue-100 text-lg mb-8">
              {t('home.readyDesc')}
            </p>
            <div className="flex flex-col sm:flex-row justify-center gap-4">
              <Link to={`${publicPrefix}/register`}>
                <Button
                  size="lg"
                  variant="outline"
                  className="
        bg-white
        text-blue-800
        border border-white/80
        text-xs font-semibold
        px-5 py-2
        shadow-sm
        transition-all duration-200
        hover:bg-emerald-50
        hover:text-emerald-700
        hover:shadow-md
        hover:-translate-y-[1px]
      "
                >
                  {t('home.freeRegister')}
                </Button>
              </Link>
              <Link to={`${publicPrefix}/search`}>
                <Button
                  size="lg"
                  variant="outline"
                  className="
        bg-white
        text-blue-800
        border border-white/80
        text-xs font-semibold
        px-5 py-2
        shadow-sm
        transition-all duration-200
        hover:bg-emerald-50
        hover:text-emerald-700
        hover:shadow-md
        hover:-translate-y-[1px]
      "
                >
                  {t('home.exploreNow')}
                </Button>
              </Link>
            </div>
            <p className="text-xs text-blue-300 mt-6">
              {t('home.noCredit')}
            </p>
          </div>
        </section>
      )}

      {reviewModalOpen && (
        <div className="fixed inset-0 z-50 bg-gray-900/60 backdrop-blur-sm flex items-center justify-center px-4">
          <div className="w-full max-w-lg bg-white rounded-2xl shadow-2xl border border-gray-100 overflow-hidden">
            <div className="flex items-start justify-between gap-4 p-6 border-b border-gray-100">
              <div>
                <p className="text-xs font-bold text-blue-600 uppercase tracking-wide">
                  Library74 Review
                </p>
                <h3 className="text-2xl font-bold text-gray-900 mt-1">
                  {myReview ? t('home.reviewModalEditTitle') : t('home.reviewModalTitle')}
                </h3>
                <p className="text-sm text-gray-500 mt-2">
                  {t('home.reviewModalDesc')}
                </p>
              </div>
              <button
                type="button"
                onClick={() => setReviewModalOpen(false)}
                className="p-2 rounded-lg text-gray-400 hover:text-gray-700 hover:bg-gray-100 transition-colors"
              >
                <X size={20} />
              </button>
            </div>

            <div className="p-6 space-y-5">
              <div>
                <label className="block text-sm font-semibold text-gray-800 mb-2">
                  {t('home.reviewSatisfactionLevel')}
                </label>
                <ReviewStarsInput value={reviewRating} onChange={setReviewRating} />
                <p className="text-xs text-gray-500 mt-2">
                  {t('home.reviewSatisfactionRule')}
                </p>
              </div>

              <div>
                <label className="block text-sm font-semibold text-gray-800 mb-2">
                  {t('home.reviewYourThoughts')}
                </label>
                <textarea
                  value={reviewComment}
                  onChange={(event) => setReviewComment(event.target.value)}
                  rows={5}
                  maxLength={1200}
                  placeholder={t('home.reviewPlaceholder')}
                  className="w-full rounded-xl border border-gray-200 px-4 py-3 text-sm text-gray-900 focus:border-blue-500 focus:ring-2 focus:ring-blue-100 outline-none resize-none"
                />
                <div className="flex justify-between mt-2 text-xs text-gray-400">
                  <span>{t('home.reviewWritingHint')}</span>
                  <span>{reviewComment.length}/1200</span>
                </div>
              </div>

              {reviewMessage && (
                <div className="rounded-xl bg-red-50 text-red-700 text-sm px-4 py-3">
                  {reviewMessage}
                </div>
              )}
            </div>

            <div className="flex flex-col sm:flex-row sm:justify-end gap-3 px-6 py-4 bg-gray-50 border-t border-gray-100">
              <Button
                variant="outline"
                onClick={() => setReviewModalOpen(false)}
                disabled={reviewSubmitting}
              >
                {t('common.cancel')}
              </Button>
              <Button
                onClick={submitSystemReview}
                disabled={reviewSubmitting}
                className="gap-2"
              >
                <Send size={16} />
                {reviewSubmitting ? t('common.saving') : myReview ? t('home.editSystemReview') : t('home.submitSystemReview')}
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default HomePage;
